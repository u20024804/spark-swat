package org.apache.spark.rdd.cl

import scala.reflect.ClassTag
import scala.reflect._
import scala.reflect.runtime.universe._

import java.net._
import java.util.LinkedList
import java.util.Map
import java.util.HashMap

import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.rdd._
import org.apache.spark.broadcast.Broadcast

import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.SparseVector

import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.Tuple2ClassModel
import com.amd.aparapi.internal.model.DenseVectorClassModel
import com.amd.aparapi.internal.model.SparseVectorClassModel
import com.amd.aparapi.internal.model.HardCodedClassModels
import com.amd.aparapi.internal.model.HardCodedClassModels.ShouldNotCallMatcher
import com.amd.aparapi.internal.model.Entrypoint
import com.amd.aparapi.internal.writer.KernelWriter
import com.amd.aparapi.internal.writer.KernelWriter.WriterAndKernel
import com.amd.aparapi.internal.writer.BlockWriter
import com.amd.aparapi.internal.writer.ScalaArrayParameter
import com.amd.aparapi.internal.writer.ScalaParameter.DIRECTION


class KernelDevicePair(val kernel : String, val dev_ctx : Long) {

  override def equals(obj : Any) : Boolean = {
    if (obj.isInstanceOf[KernelDevicePair]) {
      val other : KernelDevicePair = obj.asInstanceOf[KernelDevicePair]
      kernel.equals(other.kernel) && dev_ctx == other.dev_ctx
    } else {
      false
    }
  }

  override def hashCode() : Int = {
    dev_ctx.toInt
  }

  override def toString() : String = {
    "[" + kernel + "," + dev_ctx + "]"
  }
}

class PerThreadCache[K, V] {
  val cache : java.util.HashMap[K, java.util.LinkedList[V]] =
      new java.util.HashMap[K, java.util.LinkedList[V]]()

  def hasAny(key : K) : Boolean = {
    cache.containsKey(key) && !cache.get(key).isEmpty
  }

  def get(key : K) : V = {
    cache.get(key).poll
  }

  def add(key : K, value : V) { 
    if (!cache.containsKey(key)) {
      cache.put(key, new java.util.LinkedList[V]())
    }
    cache.get(key).add(value)
  }
}

class GlobalCache[K, V] {
  val cache : java.util.Map[Integer, PerThreadCache[K, V]] =
      new java.util.HashMap[Integer, PerThreadCache[K, V]]()
 
  def forThread(threadId : Int) : PerThreadCache[K, V] = {
    this.synchronized {
      if (cache.containsKey(threadId)) {
        cache.get(threadId)
      } else {
        val newCache = new PerThreadCache[K, V]()
        cache.put(threadId, newCache)
        newCache
      }
    }
  }
}

/*
 * Shared by all threads within a single node across a whole job, multiple tasks
 * of multiple types from multiple threads may share the data stored here.
 *
 * Initialized single-threaded.
 *
 * I believe this might actually be run single-threaded in the driver, then
 * captured in the closure handed to the workers? Having data in here might
 * drastically increase the size of the data transmitted.
 */
object CLConfig {
  val nNativeInputBuffers_str = System.getProperty("swat.n_native_input_buffers")
  val nNativeInputBuffers : Int = if (nNativeInputBuffers_str != null)
        nNativeInputBuffers_str.toInt else 2

  val nNativeOutputBuffers_str = System.getProperty("swat.n_native_output_buffers")
  val nNativeOutputBuffers : Int = if (nNativeOutputBuffers_str != null)
        nNativeOutputBuffers_str.toInt else 2

  val N_str = System.getProperty("swat.input_chunking")
  val N = if (N_str != null) N_str.toInt else 50000

  val heapSize_str = System.getProperty("swat.heap_size")
  val heapSize = if (heapSize_str != null) heapSize_str.toInt else
        64 * 1024 * 1024

  val percHighPerfBuffers_str = System.getProperty("swat.perc_high_perf_buffers")
  val percHighPerfBuffers = if (percHighPerfBuffers_str != null)
        percHighPerfBuffers_str.toDouble else 0.2

  val cl_local_size_str = System.getProperty("swat.cl_local_size")
  val cl_local_size = if (cl_local_size_str != null) cl_local_size_str.toInt else 128

  val spark_cores_str = System.getenv("SPARK_WORKER_CORES")
  assert(spark_cores_str != null)
  val spark_cores = spark_cores_str.toInt

  val heapsPerDevice_str = System.getProperty("swat.heaps_per_device")
  val heapsPerDevice = if (heapsPerDevice_str != null) heapsPerDevice_str.toInt
        else CLConfig.spark_cores

  val kernelDir_str = System.getProperty("swat.kernels_dir")
  val kernelDir = if (kernelDir_str != null) kernelDir_str else "/tmp/"

  val printKernel_str = System.getProperty("swat.print_kernel")
  val printKernel = if (printKernel_str != null) printKernel_str.toBoolean else false

  /*
   * It is possible for a single thread to have two active CLMappedRDDs if they
   * are chained together. For example, the following code:
   *
   *     val rdd1 = CLWrapper.cl(input)
   *     val rdd2 = CLWrapper.cl(rdd1.map(i => i + 1))
   *     val rdd3 = rdd2.map(i => 2 * i)
   *
   * produces two chained RDDs, both doing their maps on the GPU. However, these
   * two instances cannot share input buffers, output buffers, or SWAT contexts
   * despite being in the same thread as they may have concurrently running
   * kernels and input buffering.
   */
  val swatContextCache = new GlobalCache[KernelDevicePair, Long]()
  val inputBufferCache = new GlobalCache[String, InputBufferWrapper[_]]()
  val outputBufferCache = new GlobalCache[String, OutputBufferWrapper[_]]()
}

class CLRDDProcessor[T : ClassTag, U : ClassTag](val nested : Iterator[T],
    val userLambda : T => U, val context: TaskContext, val rddId : Int,
    val partitionIndex : Int) extends Iterator[U] {
  var entryPoint : Entrypoint = null
  var openCL : String = null

  System.setProperty("com.amd.aparapi.enable.NEW", "true");
  System.setProperty("com.amd.aparapi.enable.ATHROW", "true");

  var inputBuffer : InputBufferWrapper[T] = null
  var chunkedOutputBuffer : OutputBufferWrapper[U] = null
  var outputBuffer : Option[OutputBufferWrapper[U]] = None

  val threadId : Int = RuntimeUtil.getThreadID

  /*
   * A queue of native input buffers that are ready to be read into by the
   * reader thread.
   */
  val initiallyEmptyNativeInputBuffers : java.util.LinkedList[NativeInputBuffers[T]] =
          new java.util.LinkedList[NativeInputBuffers[T]]()
  assert(CLConfig.nNativeInputBuffers >= 2)
  val nativeInputBuffersArray : Array[NativeInputBuffers[T]] =
          new Array[NativeInputBuffers[T]](CLConfig.nNativeInputBuffers)
  val nativeOutputBuffersArray : Array[NativeOutputBuffers[U]] =
          new Array[NativeOutputBuffers[U]](CLConfig.nNativeOutputBuffers)
  /*
   * Native input buffers that have been filled by the reader thread and are
   * ready to be copied to the device.
   */
  val filledNativeInputBuffers : java.util.LinkedList[NativeInputBuffers[T]] =
          new java.util.LinkedList[NativeInputBuffers[T]]()
  /*
   * Native output buffers that have been emptied out by the writer thread and
   * can be used by a new kernel to store outputs.
   */
  val emptiedNativeOutputBuffers : java.util.LinkedList[NativeOutputBuffers[U]] =
          new java.util.LinkedList[NativeOutputBuffers[U]]()

  System.err.println("Thread = " + threadId + " N = " + CLConfig.N +
          ", cl_local_size = " + CLConfig.cl_local_size +
          ", spark_cores = " + CLConfig.spark_cores + ", stage = " +
          context.stageId + ", partition = " + context.partitionId)

  val myInputBufferCache : PerThreadCache[String, InputBufferWrapper[_]] =
    CLConfig.inputBufferCache.forThread(threadId)
  val myOutputBufferCache : PerThreadCache[String, OutputBufferWrapper[_]] =
    CLConfig.outputBufferCache.forThread(threadId)

  val isAsyncMap = (userLambda.getClass.getName == "org.apache.spark.rdd.cl.CLAsyncMappedRDD$$anonfun$1")
  val actualLambda = if (isAsyncMap) firstSample else userLambda

  val firstSample : T = nested.next
  val bufferKey : String = RuntimeUtil.getLabelForBufferCache(actualLambda, firstSample,
          CLConfig.N)

  if (myInputBufferCache.hasAny(bufferKey)) {
    assert(inputBuffer == null)
    assert(chunkedOutputBuffer == null)

    inputBuffer = myInputBufferCache.get(bufferKey)
        .asInstanceOf[InputBufferWrapper[T]]
    chunkedOutputBuffer = myOutputBufferCache.get(bufferKey)
        .asInstanceOf[OutputBufferWrapper[U]]
  }

  val classModel : ClassModel = ClassModel.createClassModel(actualLambda.getClass, null,
      new ShouldNotCallMatcher())
  val method = classModel.getPrimitiveApplyMethod
  val descriptor : String = method.getDescriptor

  // 1 argument expected for maps
  val params : LinkedList[ScalaArrayParameter] =
      CodeGenUtil.getParamObjsFromMethodDescriptor(descriptor, 1)
  params.add(CodeGenUtil.getReturnObjsFromMethodDescriptor(descriptor))

  var totalNLoaded = 0
  val overallStart = System.currentTimeMillis // PROFILE

  val partitionDeviceHint : Int = OpenCLBridge.getDeviceHintFor(
          rddId, partitionIndex, 0, 0)

//  val deviceInitStart = System.currentTimeMillis // PROFILE
  val device_index = OpenCLBridge.getDeviceToUse(partitionDeviceHint,
          threadId, CLConfig.heapsPerDevice,
          CLConfig.heapSize, CLConfig.percHighPerfBuffers,
          false)
//  System.err.println("Thread " + threadId + " selected device " + device_index) // PROFILE
  val dev_ctx : Long = OpenCLBridge.getActualDeviceContext(device_index,
          CLConfig.heapsPerDevice, CLConfig.heapSize,
          CLConfig.percHighPerfBuffers, false)
  val devicePointerSize = OpenCLBridge.getDevicePointerSizeInBytes(dev_ctx)
//  RuntimeUtil.profPrint("DeviceInit", deviceInitStart, threadId) // PROFILE

  var firstBufferOp : Boolean = true
  var sampleOutput : java.lang.Object = None

  val initializing : Boolean = (entryPoint == null)

  if (initializing) {
//     val initStart = System.currentTimeMillis // PROFILE
    sampleOutput = userLambda(firstSample).asInstanceOf[java.lang.Object]
    val entrypointAndKernel : Tuple2[Entrypoint, String] =
        if (!isAsyncMap) RuntimeUtil.getEntrypointAndKernel[T, U](firstSample,
            sampleOutput, params, userLambda, classModel, descriptor, dev_ctx,
            threadId, CLConfig.kernelDir, CLConfig.printKernel)
        else RuntimeUtil.getEntrypointAndKernel[U](sampleOutput, params,
            actualLambda.asInstanceOf[Function0[U]], classModel, descriptor,
            dev_ctx, threadId, CLConfig.kernelDir, CLConfig.printKernel)
    entryPoint = entrypointAndKernel._1
    openCL = entrypointAndKernel._2

    if (inputBuffer == null) {
      inputBuffer = RuntimeUtil.getInputBufferForSample(firstSample, CLConfig.N,
              DenseVectorInputBufferWrapperConfig.tiling,
              SparseVectorInputBufferWrapperConfig.tiling,
              entryPoint, false)

      chunkedOutputBuffer = OpenCLBridgeWrapper.getOutputBufferFor[U](
              sampleOutput.asInstanceOf[U], CLConfig.N,
              entryPoint, devicePointerSize, CLConfig.heapSize)
    }

//     RuntimeUtil.profPrint("Initialization", initStart, threadId) // PROFILE
  }

  val outArgNum : Int = inputBuffer.countArgumentsUsed
  /*
   * When used as an output, only Tuple2 uses two arguments. All other types
   * use one.
   */
  val nOutArgs : Int = if (sampleOutput.isInstanceOf[Tuple2[_, _]]) 2 else 1
  var heapArgStart : Int = -1
  var lastArgIndex : Int = -1
  var heapTopArgNum : Int = -1

//   val ctxCreateStart = System.currentTimeMillis // PROFILE
  val mySwatContextCache : PerThreadCache[KernelDevicePair, Long] =
      CLConfig.swatContextCache.forThread(threadId)

  val kernelDeviceKey : KernelDevicePair = new KernelDevicePair(
          actualLambda.getClass.getName, dev_ctx)
  if (!mySwatContextCache.hasAny(kernelDeviceKey)) {
    val ctx : Long = OpenCLBridge.createSwatContext(
              actualLambda.getClass.getName, openCL, dev_ctx, threadId,
              entryPoint.requiresDoublePragma,
              entryPoint.requiresHeap, CLConfig.N)
    mySwatContextCache.add(kernelDeviceKey, ctx)
  }
  val ctx : Long = mySwatContextCache.get(kernelDeviceKey)
  OpenCLBridge.resetSwatContext(ctx)

  for (i <- 0 until CLConfig.nNativeInputBuffers) {
    val newBuffer : NativeInputBuffers[T] = inputBuffer.generateNativeInputBuffer(dev_ctx)
    newBuffer.id = i
    initiallyEmptyNativeInputBuffers.add(newBuffer)
    nativeInputBuffersArray(i) = newBuffer
  }

  for (i <- 0 until CLConfig.nNativeOutputBuffers) {
    val newBuffer : NativeOutputBuffers[U] =
        chunkedOutputBuffer.generateNativeOutputBuffer(CLConfig.N,
                outArgNum, dev_ctx, ctx, sampleOutput.asInstanceOf[U], entryPoint)
    newBuffer.id = i
    emptiedNativeOutputBuffers.add(newBuffer)
    nativeOutputBuffersArray(i) = newBuffer
  }

  // try {
    var argnum = outArgNum + nOutArgs
  
    if (!isAsyncMap) {
      val iter = entryPoint.getReferencedClassModelFields.iterator
      while (iter.hasNext) {
        val field = iter.next
        val isBroadcast = entryPoint.isBroadcastField(field)
        argnum += OpenCLBridge.setArgByNameAndType(ctx, dev_ctx, argnum, actualLambda,
            field.getName, field.getDescriptor, entryPoint, isBroadcast)
      }
    }

    if (entryPoint.requiresHeap) {
      heapArgStart = argnum
      heapTopArgNum = heapArgStart + 1
      lastArgIndex = heapArgStart + 4
    } else {
      lastArgIndex = argnum
    }
  // } catch {
  //   case oomExc : OpenCLOutOfMemoryException => {
  //     OpenCLBridge.cleanupArguments(ctx)
  //     return new Iterator[U] {
  //       override def next() : U = { f(nested.next) }
  //       override def hasNext() : Boolean = { nested.hasNext }
  //     }
  //   }
  // }
  /*
   * Flush out the kernel arguments that never change, broadcast variables and
   * closure-captured variables
   */
  OpenCLBridge.setupGlobalArguments(ctx, dev_ctx) 

  /* BEGIN READER THREAD */
  var noMoreInputBuffering = false
  var lastSeqNo : Int = -1
  val readerRunner = new Runnable() {
    override def run() {
      var done : Boolean = false
      inputBuffer.setCurrentNativeBuffers(
              initiallyEmptyNativeInputBuffers.remove)

      while (!done) {
        val ioStart = System.currentTimeMillis // PROFILE
        inputBuffer.reset

        val myOffset : Int = totalNLoaded
        // val inputCacheId = if (firstParent[T].getStorageLevel.useMemory)
        //     new CLCacheID(firstParent[T].id, split.index, myOffset, 0)
        //     else NoCache
        val inputCacheId = NoCache

        var nLoaded : Int = -1
        val inputCacheSuccess = if (inputCacheId == NoCache) -1 else
          inputBuffer.tryCache(inputCacheId, ctx, dev_ctx, entryPoint, false)

        if (inputCacheSuccess != -1) {
          nLoaded = OpenCLBridge.fetchNLoaded(inputCacheId.rdd, inputCacheId.partition,
            inputCacheId.offset)
          /*
           * Drop the rest of this buffer from the input stream if cached,
           * accounting for the fact that some items may already have been
           * buffered from it, either in the inputBuffer (due to an overrun) or
           * because of firstSample.
           */
          val nAlreadyBuffered = if (firstBufferOp)
                 nLoaded - 1 - inputBuffer.nBuffered else
                 nLoaded - inputBuffer.nBuffered
          nested.drop(nAlreadyBuffered)
        } else {
          if (firstBufferOp) {
            inputBuffer.append(firstSample)
          }

          inputBuffer.aggregateFrom(nested)
          inputBuffer.flush

          nLoaded = inputBuffer.nBuffered
          if (inputCacheId != NoCache) {
            OpenCLBridge.storeNLoaded(inputCacheId.rdd, inputCacheId.partition,
              inputCacheId.offset, nLoaded)
          }
        }
        firstBufferOp = false
//         System.err.println("SWAT PROF " + threadId + " Loaded " + // PROFILE
//                 nLoaded + " at offset " + totalNLoaded) // PROFILE
        totalNLoaded += nLoaded

        RuntimeUtil.profPrint("Input-I/O", ioStart, threadId) // PROFILE

        /*
         * Now that we're done loading from the input stream, fetch the next
         * native input buffers to transfer any overflow into.
         */
        val nextNativeInputBuffer : NativeInputBuffers[T] =
            if (!initiallyEmptyNativeInputBuffers.isEmpty) {
              initiallyEmptyNativeInputBuffers.remove
            } else {
              val id : Int = OpenCLBridge.waitForFreedNativeBuffer(ctx, dev_ctx)
              nativeInputBuffersArray(id)
            }

        /*
         * Transfer overflow from inputBuffer.nativeBuffers/filled to
         * nextNativeInputBuffer.
         */
        inputBuffer.setupNativeBuffersForCopy(-1)
        val filled : NativeInputBuffers[T] = inputBuffer.transferOverflowTo(
                nextNativeInputBuffer)
        assert(filled.id != nextNativeInputBuffer.id)

        // Transfer input to device asynchronously
        if (filled.clBuffersReadyPtr != 0L) {
            OpenCLBridge.waitOnBufferReady(filled.clBuffersReadyPtr)
        }
        filled.copyToDevice(0, ctx, dev_ctx, inputCacheId, false)
        /*
         * Add a callback to notify the reader thread that a native input
         * buffer is now available
         */
        OpenCLBridge.enqueueBufferFreeCallback(ctx, dev_ctx, filled.id)

        val currentNativeOutputBuffer : NativeOutputBuffers[U] =
             emptiedNativeOutputBuffers.synchronized {
               while (emptiedNativeOutputBuffers.isEmpty) {
                 emptiedNativeOutputBuffers.wait
               }
               emptiedNativeOutputBuffers.poll
             }
        currentNativeOutputBuffer.addToArgs

        if (entryPoint.requiresHeap) {
          // processing_succeeded
          if (!OpenCLBridge.setArgUnitialized(ctx, dev_ctx, heapTopArgNum + 2,
                  CLConfig.N * 4, false)) {
            throw new OpenCLOutOfMemoryException();
          }
        }

        OpenCLBridge.setIntArg(ctx, lastArgIndex, nLoaded)

        done = !nested.hasNext && !inputBuffer.haveUnprocessedInputs
        val currSeqNo : Int = OpenCLBridge.getCurrentSeqNo(ctx)
        if (done) {
          /*
           * This should come before OpenCLBridge.run to ensure there is no
           * race between setting lastSeqNo and the writer thread checking it
           * after kernel completion.
           */
          assert(lastSeqNo == -1)
          lastSeqNo = currSeqNo
          inputBuffer.setCurrentNativeBuffers(null)
        }

        System.err.println("SWAT PROF " + threadId + " Kernel launch @ " + // PROFILE
            System.currentTimeMillis + " for seq = " + currSeqNo) // PROFILE

        val doneFlag : Long = OpenCLBridge.run(ctx, dev_ctx, nLoaded,
                CLConfig.cl_local_size, lastArgIndex + 1,
                heapArgStart, CLConfig.heapsPerDevice,
                currentNativeOutputBuffer.id)
        filled.clBuffersReadyPtr = doneFlag
      }
    }
  }
  var readerThread : Thread = new Thread(readerRunner)
  readerThread.start
  /* END READER THREAD */

//      RuntimeUtil.profPrint("ContextCreation", ctxCreateStart, threadId) // PROFILE
  var curr_kernel_ctx : Long = 0L
  var currentNativeOutputBuffer : NativeOutputBuffers[U] = null
  var curr_seq_no : Int = 0

  override def next() : U = {
    if (outputBuffer.isEmpty || !outputBuffer.get.hasNext) {
      if (curr_kernel_ctx > 0L) {
        System.err.println("SWAT PROF " + threadId + " Finished writing seq = " + // PROFILE
                (curr_seq_no - 1) + " @ " + System.currentTimeMillis) // PROFILE
        assert(currentNativeOutputBuffer != null)
        OpenCLBridge.cleanupKernelContext(curr_kernel_ctx)
        emptiedNativeOutputBuffers.synchronized {
          emptiedNativeOutputBuffers.add(currentNativeOutputBuffer)
          emptiedNativeOutputBuffers.notify
        }
      }
      curr_kernel_ctx = OpenCLBridge.waitForFinishedKernel(ctx, dev_ctx,
              curr_seq_no)
      System.err.println("SWAT PROF " + threadId + " Started writing seq = " + // PROFILE
              curr_seq_no + " @ " + System.currentTimeMillis) // PROFILE
      curr_seq_no += 1

      val current_output_buffer_id =
              OpenCLBridge.getOutputBufferIdFromKernelCtx(curr_kernel_ctx)
      currentNativeOutputBuffer = nativeOutputBuffersArray(
              current_output_buffer_id)

      chunkedOutputBuffer.fillFrom(curr_kernel_ctx, currentNativeOutputBuffer)

      outputBuffer = Some(chunkedOutputBuffer)
    }

    outputBuffer.get.next
  }

  def hasNext() : Boolean = {
    /*
     * hasNext may be called multiple times after running out of items, in
     * which case inputBuffer may already be null on entry to this function.
     */
    val haveNext = inputBuffer != null && (lastSeqNo == -1 ||
            curr_seq_no <= lastSeqNo || outputBuffer.get.hasNext)
    if (!haveNext && inputBuffer != null) {

      System.err.println("SWAT PROF " + threadId + " Finished writing seq = " + // PROFILE
              (curr_seq_no - 1) + " @ " + System.currentTimeMillis) // PROFILE

      for (buffer <- nativeInputBuffersArray) {
        buffer.releaseOpenCLArrays
      }
      for (buffer <- nativeOutputBuffersArray) {
        buffer.releaseOpenCLArrays
      }

      OpenCLBridge.cleanupKernelContext(curr_kernel_ctx)
      OpenCLBridge.cleanupSwatContext(ctx, dev_ctx, context.stageId,
              context.partitionId)

      val mySwatContextCache : PerThreadCache[KernelDevicePair, Long] =
          CLConfig.swatContextCache.forThread(threadId)
      val kernelDeviceKey : KernelDevicePair = new KernelDevicePair(
              actualLambda.getClass.getName, dev_ctx)
      mySwatContextCache.add(kernelDeviceKey, ctx)

      val myInputBufferCache : PerThreadCache[String, InputBufferWrapper[_]] =
        CLConfig.inputBufferCache.forThread(threadId)
      val myOutputBufferCache : PerThreadCache[String, OutputBufferWrapper[_]] =
        CLConfig.outputBufferCache.forThread(threadId)
      myInputBufferCache.add(bufferKey, inputBuffer)
      myOutputBufferCache.add(bufferKey, chunkedOutputBuffer)
      inputBuffer = null
      chunkedOutputBuffer = null

      RuntimeUtil.profPrint("Total", overallStart, threadId) // PROFILE
      System.err.println("SWAT PROF Total loaded = " + totalNLoaded) // PROFILE
    }
    haveNext
  }
}

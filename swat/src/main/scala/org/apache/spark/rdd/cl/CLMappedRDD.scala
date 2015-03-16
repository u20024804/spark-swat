package org.apache.spark.rdd.cl

import scala.reflect.ClassTag
import scala.reflect._

import java.util.LinkedList

import org.apache.spark.{Partition, TaskContext}
import org.apache.spark.rdd._

import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.Entrypoint
import com.amd.aparapi.internal.writer.KernelWriter
import com.amd.aparapi.internal.writer.KernelWriter.WriterAndKernel
import com.amd.aparapi.internal.writer.BlockWriter.ScalaParameter
import com.amd.aparapi.internal.writer.BlockWriter.ScalaParameter.DIRECTION

class CLMappedRDD[U: ClassTag, T: ClassTag](prev: RDD[T], f: T => U)
  extends RDD[U](prev) {

  override def getPartitions: Array[Partition] = firstParent[T].partitions

  override def compute(split: Partition, context: TaskContext) = {
    val N = 1024
    val acc : Array[T] = new Array[T](N)
    val output : Array[U] = new Array[U](N)

    System.setProperty("com.amd.aparapi.enable.NEW", "true");
    val classModel : ClassModel = ClassModel.createClassModel(f.getClass)
    val method = classModel.getPrimitiveApplyMethod
    val descriptor : String = method.getDescriptor

    // 1 argument expected for maps
    val params = CodeGenUtil.getParamObjsFromMethodDescriptor(descriptor, 1)
    params.add(CodeGenUtil.getReturnObjsFromMethodDescriptor(descriptor))

    val entryPoint : Entrypoint = classModel.getEntrypoint("apply", descriptor,
        f, params);

    val writerAndKernel : WriterAndKernel = KernelWriter.writeToString(
        entryPoint, params)
    val openCL : String = writerAndKernel.kernel
    val writer : KernelWriter = writerAndKernel.writer

    val ctx : Long = OpenCLBridge.createContext(openCL,
        entryPoint.requiresDoublePragma, entryPoint.requiresHeap);

    val iter = new Iterator[U] {
      val nested = firstParent[T].iterator(split, context)

      var index = 0
      var nLoaded = 0

      def next() : U = {
        if (index >= nLoaded) {
          assert(nested.hasNext)

          index = 0
          nLoaded = 0
          while (nLoaded < N && nested.hasNext) {
            acc(nLoaded) = nested.next
            nLoaded = nLoaded + 1
          }

          OpenCLBridgeWrapper.setArrayArg[T](ctx, 0, acc, entryPoint)
          OpenCLBridgeWrapper.setUnitializedArrayArg(ctx, 1, output.size,
              classTag[U].runtimeClass, entryPoint)

          var argnum : Int = 2
          val iter = entryPoint.getReferencedClassModelFields.iterator
          while (iter.hasNext) {
            val field = iter.next
            OpenCLBridge.setArgByNameAndType(ctx, argnum, f, field.getName,
                field.getDescriptor, entryPoint)
            argnum = argnum + 1
          }

          val heapArgStart : Int = argnum
          if (entryPoint.requiresHeap) {
            argnum = argnum + OpenCLBridge.createHeap(ctx, argnum, 100 * 1024L * 1024L, nLoaded)
          }   

          OpenCLBridge.setIntArg(ctx, argnum, nLoaded)

          if (entryPoint.requiresHeap) {
            val anyFailed : Array[Int] = new Array[Int](1)
            do {
              OpenCLBridge.run(ctx, nLoaded);
              OpenCLBridgeWrapper.fetchArrayArg(ctx, argnum - 1, anyFailed, entryPoint)
              OpenCLBridge.resetHeap(ctx, heapArgStart)
            } while (anyFailed(0) > 0)
          } else {
            OpenCLBridge.run(ctx, nLoaded);
          }

          OpenCLBridgeWrapper.fetchArgFromUnitializedArray(ctx, 1, output,
              entryPoint)
        }

        val curr = index
        index = index + 1
        output(curr)
      }

      def hasNext : Boolean = {
        (index < nLoaded || nested.hasNext)
      }
    }
    iter
  }
}

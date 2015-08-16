package org.apache.spark.rdd.cl

import java.nio.ByteBuffer

import com.amd.aparapi.internal.model.ClassModel
import com.amd.aparapi.internal.model.Entrypoint
import com.amd.aparapi.internal.model.ClassModel.NameMatcher
import com.amd.aparapi.internal.model.ClassModel.FieldDescriptor

trait OutputBufferWrapper[T] {
  def next() : T
  def hasNext() : Boolean
  def releaseBuffers(bbCache : ByteBufferCache)
}

class PrimitiveOutputBufferWrapper[T](val arr : Array[T])
    extends OutputBufferWrapper[T] {
  var iter : Int = 0

  override def next() : T = {
    val index = iter
    iter += 1
    arr(index)
  }

  override def hasNext() : Boolean = {
    iter < arr.length
  }

  override def releaseBuffers(bbCache : ByteBufferCache) { }
}

class ObjectOutputBufferWrapper[T](val bb : ByteBuffer, val N : Int,
        val classModel : ClassModel, val clazz : java.lang.Class[_])
    extends OutputBufferWrapper[T] {
  var iter : Int = 0
  val constructor = OpenCLBridge.getDefaultConstructor(clazz)
  val structMemberInfo : Array[FieldDescriptor] = if (classModel == null) null else classModel.getStructMemberInfoArray

  override def next() : T = {
    val new_obj : T = constructor.newInstance().asInstanceOf[T]
    OpenCLBridgeWrapper.readObjectFromStream(new_obj, classModel, bb, structMemberInfo)
    new_obj
  }

  override def hasNext() : Boolean = {
    iter < N
  }

  override def releaseBuffers(bbCache : ByteBufferCache) {
    bbCache.releaseBuffer(bb)
  }
}

class Tuple2OutputBufferWrapper(val bb1 : ByteBuffer, val bb2 : ByteBuffer,
    val N : Int, val member0Desc : String, val member1Desc : String,
    val entryPoint : Entrypoint) extends OutputBufferWrapper[Tuple2[_, _]] {
  var iter : Int = 0

  val member0Class : Class[_] = CodeGenUtil.getClassForDescriptor(member0Desc)
  val member1Class : Class[_] = CodeGenUtil.getClassForDescriptor(member1Desc)

  val member0ClassModel = if (member0Class == null) null else
        entryPoint.getModelFromObjectArrayFieldsClasses(member0Class.getName,
        new NameMatcher(member0Class.getName))
  val member1ClassModel = if (member1Class == null) null else
        entryPoint.getModelFromObjectArrayFieldsClasses(member1Class.getName,
        new NameMatcher(member1Class.getName))

  val member0Constructor = if (member0Class == null) null else
        OpenCLBridge.getDefaultConstructor(member0Class)
  val member1Constructor = if (member1Class == null) null else
        OpenCLBridge.getDefaultConstructor(member1Class)

  val member0Obj = if (member0Constructor == null) null else member0Constructor.newInstance()
  val member1Obj = if (member1Constructor == null) null else member1Constructor.newInstance()

  val structMember0Info : Array[FieldDescriptor] = if (member0ClassModel == null) null else member0ClassModel.getStructMemberInfoArray
  val structMember1Info : Array[FieldDescriptor] = if (member1ClassModel == null) null else member1ClassModel.getStructMemberInfoArray

  override def next() : Tuple2[_, _] = {
    iter += 1
    (OpenCLBridgeWrapper.readTupleMemberFromStream(member0Desc, bb1,
                                                   member0Class,
                                                   member0ClassModel,
                                                   member0Obj, structMember0Info),
     OpenCLBridgeWrapper.readTupleMemberFromStream(member1Desc, bb2,
                                                   member1Class,
                                                   member1ClassModel,
                                                   member1Obj, structMember1Info))
  }

  override def hasNext() : Boolean = {
    iter < N
  }

  override def releaseBuffers(bbCache : ByteBufferCache) {
    bbCache.releaseBuffer(bb1)
    bbCache.releaseBuffer(bb2)
  }
}

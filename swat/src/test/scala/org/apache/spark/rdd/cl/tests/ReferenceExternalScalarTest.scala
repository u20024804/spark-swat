package org.apache.spark.rdd.cl.tests

import java.util.LinkedList
import com.amd.aparapi.internal.writer.ScalaArrayParameter
import org.apache.spark.rdd.cl.CodeGenTest
import org.apache.spark.rdd.cl.CodeGenTests
import com.amd.aparapi.internal.model.HardCodedClassModels

object ReferenceExternalScalarTest extends CodeGenTest[Int, Int] {
  def getExpectedException() : String = { return null }

  def getExpectedKernel() : String = {
    val className : String = this.getClass.getSimpleName
    scala.io.Source.fromFile(CodeGenTests.testsPath +
            className.substring(0, className.length - 1) + ".kernel").mkString
  }

  def getExpectedNumInputs() : Int = {
    1
  }

  def init() : HardCodedClassModels = { new HardCodedClassModels() }

  def complete(params : LinkedList[ScalaArrayParameter]) { }

  def getFunction() : Function1[Int, Int] = {
    val v : Int = 4

    new Function[Int, Int] {
      override def apply(in : Int) : Int = {
        in + v
      }
    }
  }
}

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.cl._
import Array._
import scala.math._
import org.apache.spark.rdd._
import java.net._
import scala.io.Source

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.DenseVector

object ImagenetMerger {
    def main(args : Array[String]) {
        if (args.length != 3) {
            println("usage: ImagenetMerger input-dir output-dir npartitions")
            return
        }

        val inputDir = args(0)
        val outputDir = args(1)
        val npartitions = args(2).toInt
        val sc = get_spark_context("Imagenet Merger");

        val input : RDD[DenseVector] = sc.objectFile(inputDir)
        val coalesced : RDD[DenseVector] = input.coalesce(npartitions, false)
        coalesced.saveAsObjectFile(outputDir)
        sc.stop
    }

    def get_spark_context(appName : String) : SparkContext = {
        val conf = new SparkConf()
        conf.setAppName(appName)

        val localhost = InetAddress.getLocalHost
        // val localIpAddress = localhost.getHostAddress
        conf.setMaster("spark://" + localhost.getHostName + ":7077") // 7077 is the default port

        return new SparkContext(conf)
    }

}

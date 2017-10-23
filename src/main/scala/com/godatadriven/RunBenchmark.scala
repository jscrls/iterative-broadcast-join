package com.godatadriven

import com.godatadriven.common.Config
import com.godatadriven.generator.{DataGenerator, SkewedDataGenerator, UniformDataGenerator}
import com.godatadriven.join._
import org.apache.spark.sql.{SaveMode, SparkSession}


object RunBenchmark extends App {


  def runTest(generator: DataGenerator,
              joinType: JoinType,
              tableNameOutput: String) {

    val rows = generator.numberOfRows()

    val name = s"${generator.getName}: $joinType, passes=${Config.numberOfBroadcastPasses}, keys=${Config.numberOfKeys}, multiplier=${Config.keysMultiplier}, rows=$rows"

    println(name)

    val spark = getSparkSession(name)

    val out = joinType match {
      case _: SortMergeJoinType => NormalJoin.join(
        spark,
        spark
          .read
          .load(generator.getLargeTableName),
        spark
          .read
          .load(generator.getMediumTableName)
      )
      case _: IterativeBroadcastJoinType => IterativeBroadcastJoin.join(
        spark,
        spark
          .read
          .load(generator.getLargeTableName),
        spark
          .read
          .load(generator.getMediumTableName)
      )
    }

    out.write
      .mode(SaveMode.Overwrite)
      .parquet(tableNameOutput)

    spark.stop()
  }


  def runBenchmark(dataGenerator: DataGenerator,
                   iterations: Int = 8,
                   outputTable: String = "result.parquet"): Unit =
    (0 to iterations).foreach(step => {

      val keys = Config.numberOfKeys

      // Increment the multiplier stepwise
      val multiplier = Config.keysMultiplier + (step * Config.keysMultiplier)

      Config.keysMultiplier = multiplier

      // Generate uniform data and benchmark
      val rows = dataGenerator.numberOfRows()

      val spark = getSparkSession(s"${dataGenerator.getName}: Generate dataset with $keys keys, $rows rows")
      dataGenerator.buildTestset(
        spark,
        keysMultiplier = multiplier
      )
      spark.stop()

      Config.numberOfBroadcastPasses = 2

      runTest(
        dataGenerator,
        new IterativeBroadcastJoinType,
        outputTable
      )

      Config.numberOfBroadcastPasses = 3

      runTest(
        dataGenerator,
        new IterativeBroadcastJoinType,
        outputTable
      )

      runTest(
        dataGenerator,
        new SortMergeJoinType,
        outputTable
      )

    })

  def getSparkSession(appName: String = "Spark Application"): SparkSession = {
    val spark = SparkSession
      .builder
      .appName(appName)
      .getOrCreate()

    spark.conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    spark.conf.set("parquet.enable.dictionary", "false")
    spark.conf.set("spark.default.parallelism", Config.numberOfPartitions)
    spark.conf.set("spark.sql.shuffle.partitions", Config.numberOfPartitions)

    // Tell Spark to don't be too chatty
    spark.sparkContext.setLogLevel("WARN")

    spark
  }

  runBenchmark(UniformDataGenerator)
  runBenchmark(SkewedDataGenerator)
}

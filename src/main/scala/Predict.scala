/*
 * Copyright 2018 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.azavea.hotosmpopulation

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import astraea.spark.rasterframes._
import geotrellis.spark._
import geotrellis.raster._
import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import java.nio.file._

import cats.implicits._
import com.monovore.decline._
import geotrellis.raster.resample.Sum
import geotrellis.spark.io.hadoop.HdfsUtils
import org.apache.hadoop.fs
import org.apache.spark.ml.regression.LinearRegressionModel
import org.apache.spark.storage.StorageLevel
import spire.syntax.cfor._

object PredictApp extends CommandApp(
  name   = "predict-osm-worldpop",
  header = "Predict OSM building density from WorldPop",
  main   = {
    val worldPopUriO = Opts.option[String]("worldpop", help = "URI of WorldPop raster for a country")
    val qaTilesPathO = Opts.option[String]("qatiles", help = "Path to country QA VectorTiles mbtiles file")
    val countryCodeO = Opts.option[String]("country", help = "Country code to lookup boundary from ne_50m_admin")
    val modelUriO    = Opts.option[String]("model", help = "URI for model to be saved")
    val outputUriO   = Opts.option[String]("output", help = "URI for JSON output")

    (
      worldPopUriO, qaTilesPathO, countryCodeO, modelUriO, outputUriO
    ).mapN { (worldPopUri, qaTilesPath, countryCode, modelUri, outputUri) =>

      implicit val spark: SparkSession = SparkSession.builder().
        appName("WorldPop-OSM-Predict").
        master("local[*]").
        config("spark.ui.enabled", "true").
        config("spark.driver.maxResultSize", "2G").
        getOrCreate().
        withRasterFrames

      import spark.implicits._
      import Utils._

      println(s"Spark Configuration:")
      spark.sparkContext.getConf.getAll.foreach(println)

      val model = LinearRegressionModel.load(modelUri)

      val pop: RasterFrame = WorldPop.rasterFrame(worldPopUri, "pop")
      val popWithOsm: RasterFrame = OSM.withBuildingsRF(pop, qaTilesPath, countryCode, "osm")
      val downsampled = resampleRF(popWithOsm, 16, Sum)
      val features = Utils.explodeTiles(downsampled, filterNaN = false)
      val scored = model.transform(features)
      val assembled = Utils.assembleTiles(scored, downsampled.tileLayerMetadata.left.get)
      Output.generateJsonFromTiles(assembled, model, outputUri)
    }
  }
)
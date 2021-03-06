/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.streaming.datasource.config

import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.datasource.config.ConfigParameters._

import scala.util.Try

private[datasource]
trait ParametersUtils {

  /**
   * Spark properties
   */
  def getRememberDuration(params: Map[String, String]): Option[Long] =
    Try(params.get(RememberDuration).map(_.toLong)).getOrElse(None)

  def getStorageLevel(params: Map[String, String]): StorageLevel =
    StorageLevel.fromString(Try(params.getOrElse(StorageLevelKey, DefaultStorageLevel))
      .getOrElse(DefaultStorageLevel))

  def getStopGracefully(params: Map[String, String]): Boolean =
    Try(params.getOrElse(StopGracefully, DefaultStopGracefully.toString).toBoolean)
      .getOrElse(DefaultStopGracefully)

  def getStopSparkContext(params: Map[String, String]): Boolean =
    Try(params.getOrElse(StopSparkContext, DefaultStopSparkContext.toString).toBoolean)
      .getOrElse(DefaultStopSparkContext)

}

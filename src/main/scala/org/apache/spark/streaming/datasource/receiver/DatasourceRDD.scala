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
package org.apache.spark.streaming.datasource.receiver

import org.apache.spark.partial.{BoundedDouble, CountEvaluator, PartialResult}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.streaming.datasource.config.ParametersUtils
import org.apache.spark.streaming.datasource.models.{InputSentences, OffsetOperator}
import org.apache.spark.{Logging, Partition, TaskContext}

private[datasource]
class DatasourceRDD(
                     @transient sqlContext: SQLContext,
                     inputSentences: InputSentences,
                     datasourceParams: Map[String, String]
                   ) extends RDD[Row](sqlContext.sparkContext, Nil) with Logging with ParametersUtils {

  private var totalCalculated: Option[Long] = None

  private val InitTableName = "initTable"
  private val LimitedTableName = "limitedTable"
  private val TempInitQuery = s"select * from $InitTableName"

  val dataFrame = inputSentences.offsetConditions.fold(sqlContext.sql(inputSentences.query)) { case offset =>
    val parsedQuery = parseInitialQuery
    val conditionsSentence = offset.fromOffset.extractConditionSentence(parsedQuery)
    val orderSentence = offset.fromOffset.extractOrderSentence(parsedQuery, inverse = offset.limitRecords.isEmpty)
    val limitSentence = inputSentences.extractLimitSentence

    sqlContext.sql(parsedQuery + conditionsSentence + orderSentence + limitSentence)
  }

  private def parseInitialQuery: String = {
    if (inputSentences.query.toUpperCase.contains("WHERE") ||
      inputSentences.query.toUpperCase.contains("ORDER") ||
      inputSentences.query.toUpperCase.contains("LIMIT")
    ) {
      sqlContext.sql(inputSentences.query).registerTempTable(InitTableName)
      TempInitQuery
    } else inputSentences.query
  }

  def progressInputSentences: InputSentences = {
    if (!dataFrame.rdd.isEmpty()) {
      inputSentences.offsetConditions.fold(inputSentences) { case offset =>

        val offsetValue = if (offset.limitRecords.isEmpty)
          dataFrame.rdd.first().get(dataFrame.schema.fieldIndex(offset.fromOffset.name))
        else {
          dataFrame.registerTempTable(LimitedTableName)
          val limitedQuery = s"select * from $LimitedTableName order by ${offset.fromOffset.name} " +
            s"${OffsetOperator.toInverseOrderOperator(offset.fromOffset.operator)} limit 1"

          sqlContext.sql(limitedQuery).rdd.first().get(dataFrame.schema.fieldIndex(offset.fromOffset.name))
        }

        inputSentences.copy(offsetConditions = Option(offset.copy(fromOffset = offset.fromOffset.copy(
          value = Option(offsetValue),
          operator = OffsetOperator.toProgressOperator(offset.fromOffset.operator)))))
      }
    } else inputSentences
  }

  /**
   * Return the number of elements in the RDD. Optimized when is called the second place
   */
  override def count(): Long = {
    totalCalculated.getOrElse {
      totalCalculated = Option(dataFrame.count())
      totalCalculated.get
    }
  }

  /**
   * Return the number of elements in the RDD approximately. Optimized when count are called before
   */
  override def countApprox(
                            timeout: Long,
                            confidence: Double = 0.95): PartialResult[BoundedDouble] = {
    if (totalCalculated.isDefined) {
      val c = count()
      new PartialResult(new BoundedDouble(c, 1.0, c, c), true)
    } else {
      withScope {
        val countElements: (TaskContext, Iterator[Row]) => Long = { (ctx, iter) =>
          var result = 0L
          while (iter.hasNext) {
            result += 1L
            iter.next()
          }
          result
        }
        val evaluator = new CountEvaluator(partitions.length, confidence)
        sqlContext.sparkContext.runApproximateJob(this, countElements, evaluator, timeout)
      }
    }
  }

  /**
   * Return if the RDD is empty. Optimized when count are called before
   */
  override def isEmpty(): Boolean = {
    totalCalculated.fold {
      withScope {
        partitions.length == 0 || take(1).length == 0
      }
    } { total => total == 0L }
  }

  override def getPartitions: Array[Partition] = dataFrame.rdd.partitions

  override def compute(thePart: Partition, context: TaskContext): Iterator[Row] = dataFrame.rdd.compute(thePart, context)

  override def getPreferredLocations(thePart: Partition): Seq[String] = dataFrame.rdd.preferredLocations(thePart)
}
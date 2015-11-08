/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.stat

import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, Column, DataFrame}
import org.apache.spark.sql.catalyst.expressions.{GenericMutableRow, Cast}
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

private[sql] object StatFunctions extends Logging {

  /** Calculate the Pearson Correlation Coefficient for the given columns */
  private[sql] def pearsonCorrelation(df: DataFrame, cols: Seq[String]): Double = {
    val counts = collectStatisticalData(df, cols)
    counts.Ck / math.sqrt(counts.MkX * counts.MkY)
  }

  /** Calculate the Spearman Correlation Coefficient for the given columns */
  private[sql] def spearmanCorrelation(df: DataFrame, cols: Seq[String]): Double = {
    computeSpearmanCorrCoeff(df, cols)
  }

  /** Helper class to simplify tracking and merging counts. */
  private class CovarianceCounter extends Serializable {
    var xAvg = 0.0 // the mean of all examples seen so far in col1
    var yAvg = 0.0 // the mean of all examples seen so far in col2
    var Ck = 0.0 // the co-moment after k examples
    var MkX = 0.0 // sum of squares of differences from the (current) mean for col1
    var MkY = 0.0 // sum of squares of differences from the (current) mean for col2
    var count = 0L // count of observed examples
    // add an example to the calculation
    def add(x: Double, y: Double): this.type = {
      val deltaX = x - xAvg
      val deltaY = y - yAvg
      count += 1
      xAvg += deltaX / count
      yAvg += deltaY / count
      Ck += deltaX * (y - yAvg)
      MkX += deltaX * (x - xAvg)
      MkY += deltaY * (y - yAvg)
      this
    }
    // merge counters from other partitions. Formula can be found at:
    // http://en.wikipedia.org/wiki/Algorithms_for_calculating_variance
    def merge(other: CovarianceCounter): this.type = {
      if (other.count > 0) {
        val totalCount = count + other.count
        val deltaX = xAvg - other.xAvg
        val deltaY = yAvg - other.yAvg
        Ck += other.Ck + deltaX * deltaY * count / totalCount * other.count
        xAvg = (xAvg * count + other.xAvg * other.count) / totalCount
        yAvg = (yAvg * count + other.yAvg * other.count) / totalCount
        MkX += other.MkX + deltaX * deltaX * count / totalCount * other.count
        MkY += other.MkY + deltaY * deltaY * count / totalCount * other.count
        count = totalCount
      }
      this
    }
    // return the sample covariance for the observed examples
    def cov: Double = Ck / (count - 1)
  }

  private def collectStatisticalData(df: DataFrame, cols: Seq[String]): CovarianceCounter = {
    require(cols.length == 2, "Currently cov supports calculating the covariance " +
      "between two columns.")
    cols.map(name => (name, df.schema.fields.find(_.name == name))).foreach { case (name, data) =>
      require(data.nonEmpty, s"Couldn't find column with name $name")
      require(data.get.dataType.isInstanceOf[NumericType], "Covariance calculation for columns " +
        s"with dataType ${data.get.dataType} not supported.")
    }
    val columns = cols.map(n => Column(Cast(Column(n).expr, DoubleType)))
    df.select(columns: _*).queryExecution.toRdd.aggregate(new CovarianceCounter)(
      seqOp = (counter, row) => {
        counter.add(row.getDouble(0), row.getDouble(1))
      },
      combOp = (baseCounter, other) => {
        baseCounter.merge(other)
    })
  }

  /**
   * Calculate the covariance of two numerical columns of a DataFrame.
   * @param df The DataFrame
   * @param cols the column names
   * @return the covariance of the two columns.
   */
  private[sql] def calculateCov(df: DataFrame, cols: Seq[String]): Double = {
    val counts = collectStatisticalData(df, cols)
    counts.cov
  }

  /** Generate a table of frequencies for the elements of two columns. */
  private[sql] def crossTabulate(df: DataFrame, col1: String, col2: String): DataFrame = {
    val tableName = s"${col1}_$col2"
    val counts = df.groupBy(col1, col2).agg(count("*")).take(1e6.toInt)
    if (counts.length == 1e6.toInt) {
      logWarning("The maximum limit of 1e6 pairs have been collected, which may not be all of " +
        "the pairs. Please try reducing the amount of distinct items in your columns.")
    }
    def cleanElement(element: Any): String = {
      if (element == null) "null" else element.toString
    }
    // get the distinct values of column 2, so that we can make them the column names
    val distinctCol2: Map[Any, Int] =
      counts.map(e => cleanElement(e.get(1))).distinct.zipWithIndex.toMap
    val columnSize = distinctCol2.size
    require(columnSize < 1e4, s"The number of distinct values for $col2, can't " +
      s"exceed 1e4. Currently $columnSize")
    val table = counts.groupBy(_.get(0)).map { case (col1Item, rows) =>
      val countsRow = new GenericMutableRow(columnSize + 1)
      rows.foreach { (row: Row) =>
        // row.get(0) is column 1
        // row.get(1) is column 2
        // row.get(2) is the frequency
        val columnIndex = distinctCol2.get(cleanElement(row.get(1))).get
        countsRow.setLong(columnIndex + 1, row.getLong(2))
      }
      // the value of col1 is the first value, the rest are the counts
      countsRow.update(0, UTF8String.fromString(cleanElement(col1Item)))
      countsRow
    }.toSeq
    // Back ticks can't exist in DataFrame column names, therefore drop them. To be able to accept
    // special keywords and `.`, wrap the column names in ``.
    def cleanColumnName(name: String): String = {
      name.replace("`", "")
    }
    // In the map, the column names (._1) are not ordered by the index (._2). This was the bug in
    // SPARK-8681. We need to explicitly sort by the column index and assign the column names.
    val headerNames = distinctCol2.toSeq.sortBy(_._2).map { r =>
      StructField(cleanColumnName(r._1.toString), LongType)
    }
    val schema = StructType(StructField(tableName, StringType) +: headerNames)

    new DataFrame(df.sqlContext, LocalRelation(schema.toAttributes, table)).na.fill(0.0)
  }
  // This function will calculate Spearman's rank correlation coefficient
  // Reference: https://en.wikipedia.org/wiki/Spearman%27s_rank_correlation_coefficient
  private def computeSpearmanCorrCoeff(df: DataFrame, cols: Seq[String]): Double = {
    require(cols.length == 2, "Currently cov supports calculating the covariance " +
      "between two columns.")
    cols.map(name => (name, df.schema.fields.find(_.name == name))).foreach { case (name, data) =>
      require(data.nonEmpty, s"Couldn't find column with name $name")
      require(data.get.dataType.isInstanceOf[NumericType], "Covariance calculation for columns " +
        s"with dataType ${data.get.dataType} not supported.")
    }

    // Define column types.
    val colTypes = cols.map(n => Column(Cast(Column(n).expr, DoubleType)))
    var dfData = df.select(colTypes: _*).map(row => (row.getDouble(0), row.getDouble(1)))
    //Calculate Rank for the first column data.
    val data1Rank = getRank(dfData, true)
    //Calculate Rank for the second column data.
    val data2Rank = getRank(dfData, false)
    //Calculate sum of square of diffrence of ranks between two vector corresponding elements in original order.
    val sumSqRankDiffAndCnt = getSumSqRankDiffAndCount (dfData, data1Rank, data2Rank)
    //Length of vector.
    val dataLen = sumSqRankDiffAndCnt._2
    // Return Spearman's rank correlation coefficient.
    1 - (6 * sumSqRankDiffAndCnt._1)/(dataLen*(dataLen*dataLen -1))
  }

  // This function will calculate rank for one of two columns.
  private def getRank(dfData:RDD[(Double, Double)], bFirstCol:Boolean) : RDD[(Double, Double)] = {
    //Calculate Rank for first column data.
    dfData.map{case (( col1, col2)) => if(bFirstCol) (col1,1.0) else (col2, 1.0)}
      .sortByKey()
      .zipWithIndex()
      .map{case((col1,dummy),ind)=> (col1,((ind+1.0),1.0))}
      .reduceByKey{case((pos1, cnt1),(pos2, cnt2)) => (((pos1*cnt1+pos2*cnt2)/(cnt1+cnt2),(cnt1 + cnt2 )))}
      .map { case (col1,(rank1,cnt)) => (col1,rank1)}
  }

  // This will compute sum of square for difference in rank for corresponding ranks for individual elements, and total elements.
  private def getSumSqRankDiffAndCount(dfData:RDD[(Double, Double)],
                                       data1Rank:RDD[(Double, Double)],
                                       data2Rank:RDD[(Double, Double)]): (Double, Double) = {
    dfData.map{case (col1,col2) => (col1, col2)}
      .join(data1Rank).map{case (col1,(col2,rank1)) => (col2, (col1, rank1))}
      .join(data2Rank).map{case (col2,((col1,rank1),rank2)) => (((rank2-rank1)*(rank2-rank1)),1.0)}
      .reduce{case ((sqRankDiff1,cnt1),(sqRankDiff2,cnt2)) => (sqRankDiff1+sqRankDiff2, cnt1+cnt2)}
  }

}

/*
 * Copyright 2022 The Blaze Authors
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

package org.apache.spark.sql.execution.blaze.plan

import java.util.UUID

import org.apache.spark.InterruptibleIterator
import org.apache.spark.sql.blaze.JniBridge
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.UnaryExecNode
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.apache.spark.SparkEnv
import org.apache.spark.sql.execution.blaze.arrowio.ArrowWriterIterator
import org.apache.spark.sql.blaze.MetricNode
import org.apache.spark.sql.blaze.NativeConverters
import org.apache.spark.sql.blaze.NativeRDD
import org.apache.spark.sql.blaze.NativeSupports
import org.blaze.protobuf.PhysicalPlanNode
import org.blaze.protobuf.Schema
import org.blaze.protobuf.IpcReaderExecNode
import org.blaze.protobuf.IpcReadMode

case class ConvertToNativeExec(override val child: SparkPlan)
    extends UnaryExecNode
    with NativeSupports {
  override def nodeName: String = "ConvertToNative"

  override def logicalLink: Option[LogicalPlan] = child.logicalLink

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override lazy val metrics: Map[String, SQLMetric] =
    NativeSupports.getDefaultNativeMetrics(sparkContext)

  val renamedSchema: StructType =
    StructType(output.map(a => StructField(a.toString(), a.dataType, a.nullable, a.metadata)))
  val nativeSchema: Schema = NativeConverters.convertSchema(renamedSchema)

  override def doExecuteNative(): NativeRDD = {
    val inputRDD = child.execute()
    val numInputPartitions = inputRDD.getNumPartitions
    val timeZoneId = SparkEnv.get.conf.get(SQLConf.SESSION_LOCAL_TIMEZONE)
    val nativeMetrics = MetricNode(metrics, Nil)

    new NativeRDD(
      sparkContext,
      nativeMetrics,
      inputRDD.partitions,
      inputRDD.dependencies,
      (partition, context) => {
        val resourceId = s"ConvertToNativeExec:${UUID.randomUUID().toString}"
        JniBridge.resourcesMap.put(
          resourceId,
          () => {
            val inputRowIter = inputRDD.compute(partition, context)
            val ipcIterator =
              new ArrowWriterIterator(inputRowIter, renamedSchema, timeZoneId, context)
            new InterruptibleIterator(context, ipcIterator)
          })

        PhysicalPlanNode
          .newBuilder()
          .setIpcReader(
            IpcReaderExecNode
              .newBuilder()
              .setSchema(nativeSchema)
              .setNumPartitions(numInputPartitions)
              .setIpcProviderResourceId(resourceId)
              .setMode(IpcReadMode.CHANNEL_UNCOMPRESSED)
              .build())
          .build()
      })
  }

  override def doCanonicalize(): SparkPlan = child.canonicalized
}

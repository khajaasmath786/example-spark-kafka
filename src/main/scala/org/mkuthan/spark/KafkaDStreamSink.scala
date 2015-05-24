// Copyright (C) 2011-2012 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.mkuthan.spark

import kafka.common.TopicAndPartition
import org.apache.kafka.clients.producer.{Callback, ProducerRecord, RecordMetadata}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.{HasOffsetRanges, OffsetRange}

class KafkaDStreamSink(producer: LazyKafkaProducer, offsetStore: OffsetStore) {

  def write(ssc: StreamingContext, topic: String, stream: DStream[KafkaPayload]): Unit = {
    val topicVar = ssc.sparkContext.broadcast(topic)
    val producerVar = ssc.sparkContext.broadcast(producer)

    val successCounter = ssc.sparkContext.accumulator(0L, "success counter")
    val failureCounter = ssc.sparkContext.accumulator(0L, "failure counter")

    val callbackVar = ssc.sparkContext.broadcast(new Callback {
      override def onCompletion(recordMetadata: RecordMetadata, ex: Exception): Unit = Option(ex) match {
        case Some(ex) =>
          failureCounter += 1
        case _ =>
          successCounter += 1
      }
    })

    stream.foreachRDD { rdd =>
      val offsets = rdd.asInstanceOf[HasOffsetRanges].offsetRanges

      rdd.mapPartitionsWithIndex { (i, payloads) =>
        val offsetRange: OffsetRange = offsets(i)
        val topicAndPartition = TopicAndPartition(offsetRange.topic, offsetRange.partition)

        val topic = topicVar.value
        val producer = producerVar.value.producer
        val callback = callbackVar.value

        payloads.foreach { payload =>
          producer.send(
            new ProducerRecord(topic, payload.value),
            callback
          )
        }

        offsetStore.update(topicAndPartition, offsetRange.untilOffset)
        Iterator.empty
      }.foreach {
        ()
      }
    }
  }

}

object KafkaDStreamSink {
  def apply(config: Map[String, String], offsetStore: OffsetStore): KafkaDStreamSink = {

    val KEY_SERIALIZER = "org.apache.kafka.common.serialization.ByteArraySerializer"
    val VALUE_SERIALIZER = "org.apache.kafka.common.serialization.ByteArraySerializer"

    val defaultConfig = Map(
      "key.serializer" -> KEY_SERIALIZER,
      "value.serializer" -> VALUE_SERIALIZER
    )

    val producer = new LazyKafkaProducer(defaultConfig ++ config)

    new KafkaDStreamSink(producer, offsetStore)
  }
}
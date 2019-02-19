/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.scaladsl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.mongodb.DocumentUpdate
import akka.stream.alpakka.mongodb.scaladsl.MongoSink
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import com.mongodb.client.model.{Filters, InsertManyOptions, Updates}
import com.mongodb.reactivestreams.client.{MongoClients, MongoCollection}
import org.bson.Document
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class MongoSinkSpec
    extends WordSpec
    with ScalaFutures
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MustMatchers {

  // case class and codec for mongodb macros
  case class Number(_id: Int)

  val codecRegistry = fromRegistries(fromProviders(classOf[Number]), DEFAULT_CODEC_REGISTRY)

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()

  override protected def beforeAll(): Unit =
    Source.fromPublisher(db.drop()).runWith(Sink.head).futureValue

  private val client = MongoClients.create(s"mongodb://localhost:27017")
  private val db = client.getDatabase("MongoSinkSpec").withCodecRegistry(codecRegistry)
  private val numbersColl = db.getCollection("numbersSink")

  //#init-connection-codec
  private val numbersObjectColl: MongoCollection[Number] =
    db.getCollection("numbersSink", classOf[Number]).withCodecRegistry(codecRegistry)
  //#init-connection-codec

  implicit val defaultPatience =
    PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  override def afterEach(): Unit =
    Source.fromPublisher(numbersColl.deleteMany(new Document())).runWith(Sink.head).futureValue

  override def afterAll(): Unit =
    system.terminate().futureValue

  val testRange = 0 until 10

  def insertTestRange(): Unit =
    Source
      .fromPublisher(numbersColl.insertMany(testRange.map(i => Document.parse(s"""{"value":$i}""")).asJava))
      .runWith(Sink.head)
      .futureValue

  "MongoSinkSpec" must {

    "save with insertOne" in assertAllStagesStopped {
      // #insertOne
      val source = Source(testRange).map(i => Document.parse(s"""{"value":$i}"""))
      source.runWith(MongoSink.insertOne(numbersColl)).futureValue
      // #insertOne

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found.map(_.getInteger("value")) must contain theSameElementsAs testRange
    }

    "save with insertOne and codec support" in assertAllStagesStopped {
      val testRangeObjects = testRange.map(Number(_))
      val source = Source(testRangeObjects)

      source.runWith(MongoSink.insertOne[Number](numbersObjectColl)).futureValue

      val found = Source.fromPublisher(numbersObjectColl.find()).runWith(Sink.seq).futureValue

      found must contain theSameElementsAs testRangeObjects
    }

    "save with insertMany" in assertAllStagesStopped {
      val source = Source(testRange).map(i => Document.parse(s"""{"value":$i}"""))

      source.grouped(2).runWith(MongoSink.insertMany(numbersColl)).futureValue

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found.map(_.getInteger("value")) must contain theSameElementsAs testRange
    }

    "save with insertMany and codec support" in assertAllStagesStopped {
      // #insertMany
      val objects = testRange.map(Number(_))
      val source = Source(objects)
      source.grouped(2).runWith(MongoSink.insertMany[Number](numbersObjectColl)).futureValue
      // #insertMany

      val found = Source.fromPublisher(numbersObjectColl.find()).runWith(Sink.seq).futureValue

      found must contain theSameElementsAs objects
    }

    "save with insertMany with options" in assertAllStagesStopped {
      val source = Source(testRange).map(i => Document.parse(s"""{"value":$i}"""))

      source.grouped(2).runWith(MongoSink.insertMany(numbersColl, new InsertManyOptions().ordered(false))).futureValue

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found.map(_.getInteger("value")) must contain theSameElementsAs testRange
    }

    "save with insertMany with options and codec support" in assertAllStagesStopped {
      val testRangeObjects = testRange.map(Number(_))
      val source = Source(testRangeObjects)

      source
        .grouped(2)
        .runWith(MongoSink.insertMany[Number](numbersObjectColl, new InsertManyOptions().ordered(false)))
        .futureValue

      val found = Source.fromPublisher(numbersObjectColl.find()).runWith(Sink.seq).futureValue

      found must contain theSameElementsAs testRangeObjects
    }

    "update with updateOne" in assertAllStagesStopped {
      insertTestRange()

      // #updateOne
      val source = Source(testRange).map(
        i => DocumentUpdate(filter = Filters.eq("value", i), update = Updates.set("updateValue", i * -1))
      )
      source.runWith(MongoSink.updateOne(numbersColl)).futureValue
      // #updateOne

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found.map(doc => doc.getInteger("value") -> doc.getInteger("updateValue")) must contain theSameElementsAs testRange
        .map(i => i -> i * -1)
    }

    "update with updateMany" in assertAllStagesStopped {
      insertTestRange()

      val source = Source
        .single(0)
        .map(
          _ => DocumentUpdate(filter = Filters.gte("value", 0), update = Updates.set("updateValue", 0))
        )

      source.runWith(MongoSink.updateMany(numbersColl)).futureValue

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found.map(doc => doc.getInteger("value") -> doc.getInteger("updateValue")) must contain theSameElementsAs testRange
        .map(i => i -> 0)
    }

    "delete with deleteOne" in assertAllStagesStopped {
      insertTestRange()

      // #deleteOne
      val source = Source(testRange).map(i => Filters.eq("value", i))
      source.runWith(MongoSink.deleteOne(numbersColl)).futureValue
      // #deleteOne

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found mustBe empty
    }

    "delete with deleteMany" in assertAllStagesStopped {
      insertTestRange()

      val source = Source.single(0).map(_ => Filters.gte("value", 0))

      source.runWith(MongoSink.deleteMany(numbersColl)).futureValue

      val found = Source.fromPublisher(numbersColl.find()).runWith(Sink.seq).futureValue

      found mustBe empty
    }
  }

}

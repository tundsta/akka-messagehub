package com.messagehub

import java.net.InetSocketAddress

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.testkit.{TestActorRef, TestProbe, ImplicitSender, TestKit}
import org.scalatest._

class MessageBrokerSpec(_system: ActorSystem) extends TestKit(_system) with WordSpecLike with BeforeAndAfterAll with ImplicitSender with ShouldMatchers {

  def this() = this(ActorSystem("MessageBrokerSpec"))

  val server = TestProbe()
  val broker = system.actorOf(MessageBroker.props((ActorRefFactory, ActorRef) => server.ref))

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "A MessageBroker" when {

    "a subscriber requests subscription" should {
      "register the subscriber's socket and message filter" in {
        val actorRef = TestActorRef[MessageBroker](MessageBroker.props((f: ActorRefFactory, ActorRef) => server.ref))
        actorRef ! ServerConnection.Message("[SUBSCRIBE] Test", new InetSocketAddress(8888), new InetSocketAddress(0))
        actorRef.underlyingActor.subscribers("Test").head.getPort shouldBe 8888
      }
    }

    "a publisher publishes a message" should {
      "notify the appropriate subscribers" in {
        val actorRef = TestActorRef[MessageBroker](MessageBroker.props((f: ActorRefFactory, ActorRef) => server.ref))
        actorRef ! ServerConnection.Message("[SUBSCRIBE] Test", new InetSocketAddress(8888), new InetSocketAddress(0))

        pending
      }
    }

    "a subscriber disconnects " should {
      "remove the subscriber on disconnection" in pending
    }
  }
}

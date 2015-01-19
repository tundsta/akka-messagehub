package com.messagehub

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.ActorSystem
import akka.io.{UdpConnected, Udp, Tcp}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, ShouldMatchers, WordSpecLike}

class ClientConnectionSpec(_system: ActorSystem) extends TestKit(_system) with WordSpecLike with BeforeAndAfterAll with ImplicitSender with ShouldMatchers {

  def this() = this(ActorSystem("BrokerClientSpec"))

  trait BrokerClientCreated {
    val tcp           = TestProbe()
    val udp           = TestProbe()
    val subscriber    = TestProbe()
    val remoteAddress = new InetSocketAddress(InetAddress.getLocalHost, 0)
    val localAddress  = new InetSocketAddress(InetAddress.getLocalHost, 0)
    val client        = system.actorOf(ClientConnection.props(remoteAddress, tcpManager = tcp.ref, udpManager = udp.ref, subscriber.ref))
  }

  trait BrokerClientConnected extends BrokerClientCreated {
    client.tell(Tcp.Connected(remoteAddress, localAddress), tcp.ref)
  }

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "A BrokerClient" when {

    "started" should {

      "initiate a TCP connection to the Broker if configured" in new BrokerClientCreated {
        client ! ClientConnection.Connect("TCP")
        tcp.expectMsg(Tcp.Connect(remoteAddress))
      }

      "initiate a UDP connection to the Broker if configured" in new BrokerClientCreated {
        client ! ClientConnection.Connect("UDP")
        udp.expectMsg(UdpConnected.Connect(client, remoteAddress))
      }
    }

    "connected to a broker" should {

      "register for TCP events" in new BrokerClientConnected {
        tcp.expectMsg(Tcp.Register(client))
      }

      "route messages from the upstream client to the broker" in new BrokerClientConnected {
        client ! ClientConnection.Message("test message")

        tcp.fishForMessage() {
          case Tcp.Write(payload, _) => payload.utf8String.contains("test message")
          case _                     => false
        }
      }

      "route messages from the broker to the upstream client" in new BrokerClientConnected {
        client ! Tcp.Received(ByteString("message recd"))
        subscriber.fishForMessage() {
          case ClientConnection.Message(payload) => payload == "message recd"
          case _                                 => false
        }
      }

      "notify the client of established connections" in new BrokerClientConnected {
        subscriber.fishForMessage() {
          case ClientConnection.Connected => true
          case _                          => false
        }
      }

      "notify the client of disconnection" in new BrokerClientConnected {
        client ! Tcp.Closed
        subscriber.fishForMessage() {
          case ClientConnection.ConnectionClosed => true
          case _                                 => false
        }
      }

      "disconnect from the client on request" in new BrokerClientConnected {
        client ! ClientConnection.CloseConnection
        tcp.fishForMessage() {
          case Tcp.Close => true
          case _         => false
        }
      }
    }
  }
}

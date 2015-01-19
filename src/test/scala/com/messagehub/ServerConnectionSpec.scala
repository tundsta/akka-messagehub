package com.messagehub

import java.net.{InetAddress, InetSocketAddress}

import akka.actor._
import akka.io.Tcp
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import org.scalatest._

class ServerConnectionSpec(_system: ActorSystem) extends TestKit(_system) with WordSpecLike with BeforeAndAfterAll with ImplicitSender with ShouldMatchers {

  def this() = this(ActorSystem("ServerConnectionSpec"))

  val ignore: PartialFunction[Any, Boolean] = {
    case _ => false
  }

  val remoteAddress = new InetSocketAddress(InetAddress.getLocalHost, 0)
  val localAddress  = new InetSocketAddress(InetAddress.getLocalHost, 0)

  trait Created {
    val tcp               = TestProbe()
    val broker            = TestProbe()
    val connectionHandler = TestProbe()
    val createHandler     = (f: ActorRefFactory, r: InetSocketAddress, l: InetSocketAddress, b: ActorRef, pm: ActorRef) => f.actorOf(Props(new Wrapper(connectionHandler.ref)))
    val server            = system.actorOf(
      ServerConnection.props(broker.ref, remoteAddress, tcpManager = tcp.ref, createHandler = createHandler)
    )
  }
  class Wrapper(target: ActorRef) extends Actor {
    def receive = {
      case x => target forward x
    }
  }

  trait Connected extends Created {
    server.tell(Tcp.Connected(remoteAddress, localAddress), tcp.ref)
  }

  trait ConnectionHandlerActive {
    val broker            = TestProbe()
    val protocol = TestProbe()
    val connectionHandler = system.actorOf(Props(new ConnectionHandler(remoteAddress, localAddress, broker.ref, protocol.ref)))
  }

  "A Server" when {
    "started" should {
      "connect to tcp manager" in new Created {
        tcp.expectMsg(Tcp.Bind(server, remoteAddress))
      }
    }

    "connected" should {
      "register the connection handler for TCP events" in new Connected {
        tcp.fishForMessage() { case Tcp.Register(handler, _, _) => true; case _ => false}
      }

      "notify the broker of an established TCP connection" in new Connected {
        broker.fishForMessage() { case ServerConnection.Connected => true}
      }
      "route messages from the broker to client" in new Connected {
        val message = ServerConnection.Message("msg from broker", localAddress, remoteAddress)
        server ! message
        connectionHandler.fishForMessage() { case `message` => true; case _ => false}
      }

      "route messages from client to broker" in new ConnectionHandlerActive {
        connectionHandler ! Tcp.Received(ByteString("msg from client"))
        broker.fishForMessage() { case ServerConnection.Message("msg from client", _, `remoteAddress`) => true; case _ => false}
      }

      "notify the broker of disconnection" in new ConnectionHandlerActive {
        connectionHandler ! Tcp.Closed
        broker.fishForMessage() { case ServerConnection.ConnectionClosed => true; case _ => false}
      }
      "terminate on disconnection" in new ConnectionHandlerActive {
        broker.watch(connectionHandler)
        connectionHandler ! Tcp.Closed
        broker.fishForMessage() { case Terminated(`connectionHandler`) => true; case _ => false}

      }
    }
  }

}

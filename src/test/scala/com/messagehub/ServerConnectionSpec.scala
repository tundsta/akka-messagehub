package com.messagehub

import java.net.{InetAddress, InetSocketAddress}

import akka.actor._
import akka.io.{Udp, Tcp}
import akka.testkit._
import akka.util.ByteString
import org.scalatest._

class ServerConnectionSpec(_system: ActorSystem) extends TestKit(_system) with WordSpecLike with BeforeAndAfterAll  with ShouldMatchers {

  def this() = this(ActorSystem("ServerConnectionSpec"))

  val ignore: PartialFunction[Any, Boolean] = {
    case _ => false
  }

  val remoteAddress = new InetSocketAddress(InetAddress.getLocalHost, 0)
  val localAddress  = new InetSocketAddress(InetAddress.getLocalHost, 0)

  trait ConnectionCreated {
    val tcp                  = TestProbe("TCP-manager")
    val udp                  = TestProbe("UDP-manager")
    val broker               = TestProbe("server-client")
    val tcpConnectionHandler = TestProbe("tcp-ch")
    val udpConnectionHandler = TestProbe("udp-ch")
    val createTcpHandler     = (f: ActorRefFactory, _: InetSocketAddress, _: InetSocketAddress, _: ActorRef, _: ActorRef) => f.actorOf(Props(new ChildWrapper(tcpConnectionHandler.ref)))
    val createUdpHandler     = (f: ActorRefFactory, _: InetSocketAddress, _: ActorRef, _: ActorRef) => f.actorOf(Props(new ChildWrapper(udpConnectionHandler.ref)))
    val server               = system.actorOf(
      ServerConnection.props(broker.ref, remoteAddress, tcpManager = tcp.ref, udpManager = udp.ref, createHandler = createTcpHandler, createUdpHandler)
    )
  }
  class ChildWrapper(target: ActorRef) extends Actor {
    def receive = {
      case x => target forward x
    }
  }

  trait TcpConnected extends ConnectionCreated {
    server.tell(Tcp.Connected(remoteAddress, localAddress), tcp.ref)
  }

  trait UdpBound extends ConnectionCreated {
    server.tell(Udp.Bound(localAddress), udp.ref)
  }

  trait TcpConnectionHandlerActive {
    val broker            = TestProbe()
    val protocol          = TestProbe()
    val connectionHandler = system.actorOf(Props(new TcpConnectionHandler(remoteAddress, localAddress, broker.ref, protocol.ref)))
  }

  trait UdpConnectionHandlerCreated {
    val broker            = TestProbe()
    val udp               = TestProbe()
    val connectionHandler = system.actorOf(Props(new UdpConnectionHandler(broker.ref, udp.ref, localAddress)))
  }

  "A Server" when {
    "started" should {
      "bind to a TCP socket for incoming TCP connections" in new ConnectionCreated {
        tcp.expectMsg(Tcp.Bind(server, remoteAddress))
      }
      "bind to a UDP socket for incoming UDP connections" in new ConnectionCreated {
        udp.expectMsg(Udp.Bind(server, remoteAddress))
      }
    }

    "TCP connected" should {
      "register a  connection handler for TCP messages" in new TcpConnected {
        tcp.fishForMessage() { case Tcp.Register(handler, _, _) => true; case _ => false}
      }

      "route messages from the server to client" in new TcpConnected {
        val message = ServerConnection.Message("msg from broker", localAddress, remoteAddress)
        server ! message
        tcpConnectionHandler.fishForMessage() { case `message` => true; case _ => false}
      }

      "route messages from client to local endpoint" in new TcpConnectionHandlerActive {
        connectionHandler ! Tcp.Received(ByteString("msg from client"))
        broker.fishForMessage() { case ServerConnection.Message("msg from client", _, `remoteAddress`) => true; case _ => false}
      }

      "notify the local endpoint of disconnection" in new TcpConnectionHandlerActive {
        connectionHandler ! Tcp.Closed
        broker.fishForMessage() { case ServerConnection.ConnectionClosed => true; case _ => false}
      }
      "terminate connection handler on disconnection" in new TcpConnectionHandlerActive {
        broker.watch(connectionHandler)
        connectionHandler ! Tcp.Closed
        broker.fishForMessage() { case Terminated(`connectionHandler`) => true; case _ => false}

      }
    }
    "UDP bound" should {
      "route messages from remote client to local endpoint" in new UdpConnectionHandlerCreated {
        connectionHandler ! Udp.Received(ByteString("UDP msg from client"), remoteAddress)
        broker.expectMsg(ServerConnection.Message("UDP msg from client", remoteAddress, localAddress))
      }

      "route messages from local endpoint to remote client" in new UdpBound {
        server !  ServerConnection.Message("msg from broker to UDP client", localAddress, remoteAddress)
        udp.fishForMessage() { case Udp.Send(payload, _, _)  => true; case _ => false}
      }
    }
  }
}

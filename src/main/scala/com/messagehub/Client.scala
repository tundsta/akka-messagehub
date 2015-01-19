package com.messagehub

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{UdpConnected, Udp, Tcp}
import akka.util.ByteString

object ClientConnection {

  case class Message(payload: String)
  case class Connect(protocol: String)
  case object Connected
  case object Terminate
  case object CloseConnection
  case object ConnectionClosed

  def props(remote: InetSocketAddress, tcpManager: ActorRef, udpManager: ActorRef, client: ActorRef) = Props(classOf[ClientConnection], remote, tcpManager, udpManager, client)
}

class ClientConnection(remote: InetSocketAddress, tcpManager: ActorRef, udpManager: ActorRef, client: ActorRef) extends Actor with ActorLogging {

  import akka.io.Tcp._

  def receive = {
    case CommandFailed(_: Connect)          => terminate()
    case ClientConnection.Connect(protocol) => connect(protocol)
    case Tcp.Connected(remoteAddress, _)    => registerTcp()
    case UdpConnected.Connected             => context become udpConnected(sender())

  }


  def connect(protocol: String): Unit = {
    log.info(s"connecting to {} via {}...", remote, protocol)
    protocol match {
      case "TCP" => tcpManager ! Tcp.Connect(remote)
      case "UDP" => udpManager ! UdpConnected.Connect(self, remote)
      case _     => log.error("Invalid protocol"); terminate()
    }
  }

  def registerTcp(): Unit = {
    val tcp = sender()
    log.info("subscriber {} connected to tcp manager: {}", client, tcp)
    tcp ! Register(self)
    client ! ClientConnection.Connected
    context become tcpConnected(tcp)
  }

  def tcpConnected(tcp: ActorRef): Receive = {
    case ClientConnection.Message(data)   => log.info("sending {}", data); tcp ! Tcp.Write(ByteString(data + ";", "UTF-8"))
    case Tcp.Received(data)               => client ! ClientConnection.Message(data.utf8String)
    case ClientConnection.CloseConnection => tcp ! Tcp.Close
    case _: Tcp.ConnectionClosed          => client ! ClientConnection.ConnectionClosed; terminate()
  }

  def udpConnected(udp: ActorRef): Receive = {
    case UdpConnected.Received(data) => client ! ClientConnection.Message(data.utf8String)
    case msg: String                 => udp ! UdpConnected.Send(ByteString(msg))
    case d @ UdpConnected.Disconnect => udp ! d
    case UdpConnected.Disconnected   => log.info("...terminating"); terminate()
  }


  def terminate() = context stop self
}

object Subscriber {

  def props(messageFilter: String, protocol: String, createBrokerClient: (ActorRefFactory, ActorRef) => ActorRef) =
    Props(new Subscriber(messageFilter, protocol, createBrokerClient))
}

class Subscriber(messageFilter: String, protocol: String, createClientConnection: (ActorRefFactory, ActorRef) => ActorRef) extends Actor with ActorLogging {
  val connection = createClientConnection(context, self)

  connection ! ClientConnection.Connect(protocol)

  def receive: Receive = {
    case ClientConnection.Connected        => connection ! ClientConnection.Message(s"[SUBSCRIBE] $messageFilter")
    case ClientConnection.Message(payload) => log.info("received {}", payload)
    case ClientConnection.ConnectionClosed => self ! PoisonPill
  }
}

object Publisher {
  case class Publish(message: String)

  def props(protocol: String, createBrokerClient: (ActorRefFactory, ActorRef) => ActorRef) =
    Props(new Publisher(protocol, createBrokerClient))

}
class Publisher(protocol: String, createClientConnection: (ActorRefFactory, ActorRef) => ActorRef) extends Actor with ActorLogging {
  val connection = createClientConnection(context, self)

  connection ! ClientConnection.Connect(protocol)

  def receive: Receive = {
    case ClientConnection.Connected        => log.info("...connected via {}", protocol)
    case Publisher.Publish(message)        => connection ! ClientConnection.Message(message)
    case ClientConnection.ConnectionClosed => self ! PoisonPill

  }
}



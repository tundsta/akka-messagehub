package com.messagehub

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{Udp, Tcp}
import akka.util.ByteString

class TcpConnectionHandler(remote: InetSocketAddress, local: InetSocketAddress, broker: ActorRef, tcp: ActorRef) extends Actor with ActorLogging {

  def receive = {
    case Tcp.Received(data)                             => frameDelimit(data.utf8String) foreach (broker ! _)
    case ServerConnection.Message(payload, _, `remote`) => log.info("dispatching message to {}...", remote); tcp ! Tcp.Write(ByteString(payload))
    case _: Tcp.ConnectionClosed                        => log.info("remote address {} closed", remote); broker ! ServerConnection.ConnectionClosed; context stop self
  }

  def frameDelimit(aggregated: String) = aggregated.split(";").map {
    m => ServerConnection.Message(payload = m, originAddress = remote, destinationAddress = local)
  }
}

class UdpConnectionHandler(broker: ActorRef, udp: ActorRef, local: InetSocketAddress) extends Actor with ActorLogging {

  def receive = {
    case Udp.Received(data, remoteAddress)          => broker ! ServerConnection.Message(data.utf8String, originAddress = remoteAddress, destinationAddress = local)
    case Udp.Unbind                                 => udp ! Udp.Unbind
    case Udp.Unbound                                => context stop self
    case ServerConnection.Message(payload, _, dest) => udp ! Udp.Send(ByteString(payload), dest)
  }

  //
  //  def frameDelimit(aggregated: String) = aggregated.split(";").map {
  //    m => ServerConnection.Message(payload = m, originAddress = remote, destinationAddress = local)
  //  }
}
class ServerConnection(broker: ActorRef,
                       serverEndpoint: InetSocketAddress,
                       tcpManager: ActorRef,
                       udpManager: ActorRef,
                       createTcpHandler: (ActorRefFactory, InetSocketAddress, InetSocketAddress, ActorRef, ActorRef) => ActorRef,
                       createUdpHandler: (ActorRefFactory, InetSocketAddress, ActorRef, ActorRef) => ActorRef) extends Actor with ActorLogging {

  tcpManager ! Tcp.Bind(self, serverEndpoint)
  log.info("binding to {} TCP socket...", serverEndpoint)
  udpManager ! Udp.Bind(self, serverEndpoint)
  log.info("binding to {}  UDP socket...", serverEndpoint)

  override def receive: Receive = {
    case Tcp.Connected(client, _)       => registerTcp(client)
    case Tcp.CommandFailed(_: Tcp.Bind) => terminate()
    case m: ServerConnection.Message    => context.children.foreach(_ ! m)
    case Udp.Bound(localAddress)        => createUdpHandler(localAddress)
  }


  def createUdpHandler(localAddress: InetSocketAddress) {
    val udp = sender()
    context.actorOf(Props(new UdpConnectionHandler(broker, udp = udp, local = localAddress)), "udpConnectionHandler")
  }

  def registerTcp(remote: InetSocketAddress) {
    sender ! Tcp.Register(createTcpHandler(context, remote, serverEndpoint, broker, sender()))
    log.info("Remote client {} connected", remote)
  }

  def terminate() = context.stop(self)

}

object ServerConnection {
  object ConnectionClosed
  case class Message(payload: String, originAddress: InetSocketAddress, destinationAddress: InetSocketAddress)

  def props(broker: ActorRef,
            endpoint: InetSocketAddress,
            tcpManager: ActorRef,
            udpManager: ActorRef,
            createHandler: (ActorRefFactory, InetSocketAddress, InetSocketAddress, ActorRef, ActorRef) => ActorRef,
            createUdpHandler: (ActorRefFactory, InetSocketAddress, ActorRef, ActorRef) => ActorRef): Props =
    Props(new ServerConnection(broker, endpoint, tcpManager, udpManager, createHandler, createUdpHandler))
}
package com.messagehub

import java.net.InetSocketAddress

import akka.actor._
import akka.io.Tcp
import akka.util.ByteString

class ConnectionHandler(remote: InetSocketAddress, local: InetSocketAddress, broker: ActorRef, protocolManager: ActorRef) extends Actor with ActorLogging {

  def receive = {
    case Tcp.Received(data)                             => frameDelimit(data.utf8String) foreach (broker ! _)
    case ServerConnection.Message(payload, _, `remote`) => log.info("dispatching message to {}...", remote); protocolManager ! Tcp.Write(ByteString(payload))
    case _: Tcp.ConnectionClosed                        => log.info("remote address {} closed", remote); broker ! ServerConnection.ConnectionClosed; context stop self
  }

  def frameDelimit(aggregated: String) = aggregated.split(";").map {
    m => ServerConnection.Message(payload = m, originAddress = remote, destinationAddress = local)
  }
}

class ServerConnection(broker: ActorRef,
                       serverEndpoint: InetSocketAddress,
                       tcpManager: ActorRef,
                       createHandler: (ActorRefFactory, InetSocketAddress, InetSocketAddress, ActorRef, ActorRef) => ActorRef) extends Actor with ActorLogging {

  tcpManager ! Tcp.Bind(self, serverEndpoint)
  log.info("server waiting for connection: {}", serverEndpoint)

  override def receive: Receive = {
    case Tcp.CommandFailed(_: Tcp.Bind) => terminate()
    case Tcp.Connected(remote, _)       => register(remote)
    case m: ServerConnection.Message    => context.children.foreach(_ ! m)
  }

  def register(remote: InetSocketAddress) {
    sender ! Tcp.Register(createHandler(context, remote, serverEndpoint, broker, sender()))
    log.info("Remote client {} connected", remote)
    broker ! ServerConnection.Connected
  }

  def terminate() = context.stop(self)

}

object ServerConnection {
  object Connected
  object ConnectionClosed
  case class Message(payload: String, originAddress: InetSocketAddress, destinationAddress: InetSocketAddress)

  def props(broker: ActorRef,
            endpoint: InetSocketAddress,
            tcpManager: ActorRef,
            createHandler: (ActorRefFactory, InetSocketAddress, InetSocketAddress, ActorRef, ActorRef) => ActorRef): Props =
    Props(new ServerConnection(broker, endpoint, tcpManager, createHandler))
}
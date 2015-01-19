package com.messagehub

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp, UdpConnected}


object MessageHubApp extends App {

  implicit val system = ActorSystem("message-hub-system")

  val endpoint                      = new InetSocketAddress("localhost", 12121)
  val createClientConnection        = (f: ActorRefFactory, client: ActorRef) => f.actorOf(ClientConnection.props(remote = endpoint, tcpManager = IO(Tcp), udpManager = IO(UdpConnected), client = client))
  val createServerConnectionHandler = (f: ActorRefFactory, remote: InetSocketAddress, serverEndpoint: InetSocketAddress, broker: ActorRef, protocolManager: ActorRef) => f.actorOf(
    Props(new ConnectionHandler(remote, serverEndpoint, broker = broker, protocolManager = protocolManager)), s"${remote.getPort}-ConnectionHandler"
  )
  val createServerConnection        = (f: ActorRefFactory, broker: ActorRef) => f.actorOf(ServerConnection.props(broker = broker, endpoint = endpoint, tcpManager = IO(Tcp), createServerConnectionHandler))

  system.actorOf(MessageBroker.props(createServerConnection), "Broker")
  system.actorOf(Subscriber.props("Test Message 1", "TCP", createClientConnection), "Subscriber1")
  system.actorOf(Subscriber.props("Test Message 2", "TCP", createClientConnection), "Subscriber2")

  val publisher =  system.actorOf(Publisher.props("TCP", createClientConnection), "Publisher1")

  Thread.sleep(1000)
  publisher ! Publisher.Publish("Test Message 1")
  publisher ! Publisher.Publish("Test Message 2")
  publisher ! Publisher.Publish("Test Message 3")
  scala.io.StdIn.readLine(s"Hit ENTER to exit ...${System.getProperty("line.separator")}")
  system.shutdown()
}


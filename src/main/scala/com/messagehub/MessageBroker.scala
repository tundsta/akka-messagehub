package com.messagehub

import java.net.InetSocketAddress

import akka.actor._

object MessageBroker {
  def props(createServer: (ActorRefFactory, ActorRef) => ActorRef) = Props(new MessageBroker(createServer))
}

class MessageBroker(createServer: (ActorRefFactory, ActorRef) => ActorRef) extends Actor with ActorLogging {

  var subscribers: Map[String, Seq[InetSocketAddress]] = Map.empty
  val connection                                       = createServer(context, self)

  def receive: Receive = {
    case m @ ServerConnection.Message(payload, origin, _) =>
      if (payload.startsWith("[SUBSCRIBE]")) {
        val filter = payload.replace("[SUBSCRIBE]", "").trim
        subscribers = subscribers + createSubscriber(subscribers, filter, origin)
        log.info("...subscribed client {} with subscription {}", origin, payload)
      } else {
        log.info("Broker received message: {}", m)
        filterSubscribers(subscribers, m).foreach(a => connection ! m.copy(destinationAddress = a))
      }
  }

  def filterSubscribers(subs: Map[String, Seq[InetSocketAddress]], m: ServerConnection.Message) =
    subs.collect { case (filter, address) if m.payload.contains(filter) => address}.toSeq.flatten

  def createSubscriber(existingSubscribers: Map[String, Seq[InetSocketAddress]],
                       messageFilter: String,
                       origin: InetSocketAddress) =
    existingSubscribers.get(messageFilter).map { addresses => messageFilter -> (addresses :+ origin)
    }.getOrElse(messageFilter -> Seq(origin))

}

package net.consensys.wittgenstein.core;

import java.util.Arrays;
import java.util.List;

/**
 * This is a class internal to the framework.
 */
abstract class Envelope<TN extends Node> {
  abstract Network.Message<TN> getMessage();

  abstract int getNextDestId();

  abstract int nextArrivalTime(Network network);

  abstract Envelope<?> getNextSameTime();

  abstract void setNextSameTime(Envelope<?> m);

  abstract void markRead();

  abstract boolean hasNextReader();

  abstract int getFromId();

  /**
   * The implementation idea here is the following: - we expect that messages are the bottleneck -
   * we expect that we have a lot of single messages sent to multiple nodes, many thousands - this
   * has been confirmed by looking at the behavior with youkit 95% of the memory is messages - so we
   * want to optimize this case. - we have a single MultipleDestEnvelope for all nodes - we don't
   * keep the list of the network latency to save memory
   * <p>
   * To avoid storing the network latencies, we do: - generate the randomness from a unique per
   * MultipleDestEnvelope + the node id - sort the nodes with the calculated latency (hence the first
   * node is the first to receive the message) - recalculate them on the fly as the nodeId & the
   * randomSeed are kept. - this also allows on disk serialization
   */
  final static class MultipleDestEnvelope<TN extends Node> extends Envelope<TN> {
    final Network.Message<TN> message;
    private final int fromNodeId;

    private final int sendTime;
    final int randomSeed;
    private final int[] destIds;
    private int curPos = 0;
    private Envelope<?> nextSameTime = null;

    MultipleDestEnvelope(Network.Message<TN> m, Node fromNode,
                         List<Network.MessageArrival> dests, int sendTime, int randomSeed) {
      this.message = m;
      this.fromNodeId = fromNode.nodeId;
      this.randomSeed = randomSeed;
      this.destIds = new int[dests.size()];

      for (int i = 0; i < destIds.length; i++) {
        destIds[i] = dests.get(i).dest.nodeId;
      }
      this.sendTime = sendTime;
    }

    @Override
    public String toString() {
      return "Envelope{" + "message=" + message + ", fromNode=" + fromNodeId
          + ", dests=" + Arrays.toString(destIds) + ", curPos=" + curPos + '}';
    }

    @Override
    Network.Message<TN> getMessage() {
      return message;
    }

    @Override
    int getNextDestId() {
      return destIds[curPos];
    }

    int nextArrivalTime(Network network) {
      return sendTime
          + network.networkLatency.getLatency((Node) network.allNodes.get(this.fromNodeId),
              (Node) network.allNodes.get(this.getNextDestId()),
              Network.getPseudoRandom(this.getNextDestId(), randomSeed));
    }

    @Override
    Envelope<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Envelope<?> m) {
      this.nextSameTime = m;
    }

    void markRead() {
      curPos++;
    }

    boolean hasNextReader() {
      return curPos < destIds.length;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }
  }


  final static class SingleDestEnvelope<TN extends Node> extends Envelope<TN> {
    final Network.Message<TN> message;
    private final int fromNodeId;
    private final int toNodeId;
    private final int arrivalTime;
    private Envelope<?> nextSameTime = null;


    @Override
    Envelope<?> getNextSameTime() {
      return nextSameTime;
    }

    @Override
    void setNextSameTime(Envelope<?> nextSameTime) {
      this.nextSameTime = nextSameTime;
    }

    SingleDestEnvelope(Network.Message<TN> message, Node fromNode, Node toNode,
                       int arrivalTime) {
      this.message = message;
      this.fromNodeId = fromNode.nodeId;
      this.toNodeId = toNode.nodeId;
      this.arrivalTime = arrivalTime;
    }

    @Override
    public String toString() {
      return "Envelope{" + "message=" + message + ", fromNode=" + fromNodeId
          + ", dest=" + toNodeId + '}';
    }

    @Override
    Network.Message<TN> getMessage() {
      return message;
    }

    @Override
    int getNextDestId() {
      return toNodeId;
    }

    int nextArrivalTime(Network network) {
      return arrivalTime;
    }

    void markRead() {}

    boolean hasNextReader() {
      return false;
    }

    @Override
    int getFromId() {
      return fromNodeId;
    }
  }
}
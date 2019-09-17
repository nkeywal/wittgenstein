package net.consensys.wittgenstein.protocols;

import java.io.File;
import java.io.IOException;
import java.util.*;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;

/**
 * A p2p protocol for BLS signature aggregation.
 *
 * <p>A node: Sends its states to all its direct peers whenever it changes Keeps the list of the
 * states of its direct peers Sends, every x milliseconds, to one of its peers a set of missing
 * signatures Runs in parallel a task to validate the signatures sets it has received. Send only
 * validated signatures to its peers.
 */
@SuppressWarnings("WeakerAccess")
public class P2PSignature implements Protocol {
  P2PSignatureParameters params;
  final P2PNetwork<P2PSigNode> network;
  final NodeBuilder nb;

  enum SendSigsStrategy {
    /** Send all signatures, not taking the state into account */
    all,
    /** send just the diff (we need the state of the other nodes for this) */
    dif,
    /** send all the signatures, but compress them */
    cmp_all,
    /** compress, but sends the compress diff if it's smaller. */
    cmp_diff
  }

  public static class P2PSignatureParameters extends WParameters {

    /** The number of nodes in the network participating in signing */
    final int signingNodeCount;

    /** The number of nodes participating without signing. */
    final int relayingNodeCount;

    /** The number of signatures to reach to finish the protocol. */
    final int threshold;

    /** The typical number of peers a peer has. At least 3. */
    final int connectionCount;

    /** The time it takes to do a pairing for a node. */
    final int pairingTime;

    /** The protocol sends a set of sigs every 'sigsSendPeriod' milliseconds */
    final int sigsSendPeriod;

    /** @see P2PSigNode#sendSigs for the two strategies on aggregation. */
    final boolean doubleAggregateStrategy;

    /**
     * If true the nodes send their state to the peers they are connected with. If false they don't.
     */
    final boolean withState = true;

    /** Use san fermin in parallel with gossiping. */
    final boolean sanFermin;

    /** For the compression scheme: we can use log 2 (the default or other values). */
    final int sigRange;

    final String nodeBuilderName;
    final String networkLatencyName;

    final SendSigsStrategy sendSigsStrategy;

    public P2PSignatureParameters() {
      this.signingNodeCount = 100;
      this.relayingNodeCount = 20;
      this.threshold = 99;
      this.connectionCount = 40;
      this.pairingTime = 100;
      this.sigsSendPeriod = 1000;
      this.doubleAggregateStrategy = true;
      this.sanFermin = true;
      this.sendSigsStrategy = this.sanFermin ? SendSigsStrategy.cmp_all : SendSigsStrategy.dif;
      this.sigRange = 20;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public P2PSignatureParameters(
        int signingNodeCount,
        int relayingNodeCount,
        int threshold,
        int connectionCount,
        int pairingTime,
        int sigsSendPeriod,
        boolean doubleAggregateStrategy,
        boolean sanFermin,
        SendSigsStrategy sendSigsStrategy,
        int sigRange,
        String nodeBuilderName,
        String networkLatencyName) {
      this.signingNodeCount = signingNodeCount;
      this.relayingNodeCount = relayingNodeCount;
      this.threshold = threshold;
      this.connectionCount = connectionCount;
      this.pairingTime = pairingTime;
      this.sigsSendPeriod = sigsSendPeriod;
      this.doubleAggregateStrategy = doubleAggregateStrategy;
      this.sanFermin = sanFermin;
      this.sendSigsStrategy = this.sanFermin ? SendSigsStrategy.cmp_all : sendSigsStrategy;
      this.sigRange = sigRange;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public P2PSignature(P2PSignatureParameters params) {
    this.params = params;
    this.network = new P2PNetwork<>(params.connectionCount, false);
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  static class State extends Message<P2PSigNode> {
    final BitSet desc;
    final P2PSigNode who;

    public State(P2PSigNode who) {
      this.desc = (BitSet) who.verifiedSignatures.clone();
      this.who = who;
    }

    /**
     * By convention, all the last bits are implicitly set to zero, so we don't always have to
     * transport the full state.
     */
    @Override
    public int size() {
      return Math.max(1, desc.length() / 8);
    }

    @Override
    public void action(Network<P2PSigNode> network, P2PSigNode from, P2PSigNode to) {
      to.onPeerState(this);
    }
  }

  /**
   * Calculate the number of signatures we have to include is we apply a compression strategy
   * Strategy is: - we divide the bitset in ranges of size sigRange - all the signatures at the
   * beginning of a range are aggregated, until one of them is not available.
   *
   * <p>Example for a range of size 4:</br> 1101 0111 => we have 5 sigs instead of 6</br> 1111 1110
   * => we have 2 sigs instead of 7</br> 0111 0111 => we have 6 sigs </br>
   *
   * <p>Note that we don't aggregate consecutive ranges, because we would not be able to merge
   * bitsets later.</br> For example, still with a range of for, with two nodes:</br> node 1: 1111
   * 1111 0000 => 2 sigs, and not 1</br> node 2: 0000 1111 1111 => again, 2 sigs and not 1</br>
   *
   * <p>By keeping the two aggregated signatures node 1 & node 2 can exchange aggregated signatures.
   * 1111 1111 => 1 0001 1111 1111 0000 => 3 0001 1111 1111 1111 => 2 </>
   *
   * @return the number of signatures to include
   */
  int compressedSize(BitSet sigs) {
    if (sigs.length() == params.signingNodeCount) {
      // Shortcuts: if we have all sigs, then we just send
      //  an aggregated signature
      return 1;
    }

    int firstOneAt = -1;
    int sigCt = 0;
    int pos = -1;
    boolean compressing = false;
    boolean wasCompressing = false;
    while (++pos <= sigs.length() + 1) {
      if (!sigs.get(pos)) {
        compressing = false;
        sigCt -= mergeRanges(firstOneAt, pos);
        firstOneAt = -1;
      } else if (compressing) {
        if ((pos + 1) % params.sigRange == 0) {
          // We compressed the whole range, but now we're starting a new one...
          compressing = false;
          wasCompressing = true;
        }
      } else {
        sigCt++;
        if (pos % params.sigRange == 0) {
          compressing = true;
          if (!wasCompressing) {
            firstOneAt = pos;
          } else {
            wasCompressing = false;
          }
        }
      }
    }

    return sigCt;
  }

  /**
   * Merging can be combined, so this function is recursive. For example, for a range size of 2, if
   * we have 11 11 11 11 11 11 11 11 11 11 11 => 11 sigs w/o merge.</br> This should become 3 after
   * merge: the first 8, then the second set of two blocks
   */
  private int mergeRanges(int firstOneAt, int pos) {
    if (firstOneAt < 0) {
      return 0;
    }
    // We start only at the beginning of a range
    if (firstOneAt % (params.sigRange * 2) != 0) {
      firstOneAt += (params.sigRange * 2) - (firstOneAt % (params.sigRange * 2));
    }

    int rangeCt = (pos - firstOneAt) / params.sigRange;
    if (rangeCt < 2) {
      return 0;
    }

    int max = MoreMath.log2(rangeCt);
    while (max > 0) {
      int sizeInBlocks = (int) Math.pow(2, max);
      int size = sizeInBlocks * params.sigRange;
      if (firstOneAt % size == 0) {
        return (sizeInBlocks - 1) + mergeRanges(firstOneAt + size, pos);
      }
      max--;
    }

    return 0;
  }

  static class SendSigs extends Message<P2PSigNode> {
    final BitSet sigs;
    final int size;

    public SendSigs(BitSet sigs) {
      this(sigs, sigs.cardinality());
    }

    public SendSigs(BitSet sigs, int sigCount) {
      this.sigs = (BitSet) sigs.clone();
      // Size = bit field + the signatures included
      this.size = Math.max(1, sigs.length() / 8 + sigCount * 48);
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public void action(Network<P2PSigNode> network, P2PSigNode from, P2PSigNode to) {
      to.onNewSig(sigs);
    }
  }

  public class P2PSigNode extends P2PNode<P2PSigNode> {
    final BitSet verifiedSignatures = new BitSet(params.signingNodeCount);
    final Set<BitSet> toVerify = new HashSet<>();
    final Map<Integer, State> peersState = new HashMap<>();
    final boolean justRelay;

    boolean done = false;

    P2PSigNode(boolean justRelay) {
      super(network.rd, nb);
      this.justRelay = justRelay;
      if (!justRelay) {
        verifiedSignatures.set(nodeId, true);
      }
    }

    public BitSet sanFerminPeers(int round) {
      if (round < 1) {
        throw new IllegalArgumentException("round=" + round);
      }
      BitSet res = new BitSet(params.signingNodeCount);
      int cMask = (1 << round) - 1;
      int start = (cMask | nodeId) ^ cMask;
      int end = nodeId | cMask;
      end = Math.min(end, params.signingNodeCount - 1);
      res.set(start, end + 1);
      res.set(nodeId, false);

      return res;
    }

    /** Asynchronous, so when we receive a state it can be an old one. */
    void onPeerState(State state) {
      int newCard = state.desc.cardinality();
      State old = peersState.get(state.who.nodeId);

      if (newCard < params.threshold && (old == null || old.desc.cardinality() < newCard)) {
        peersState.put(state.who.nodeId, state);
      }
    }

    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message.
     */
    void updateVerifiedSignatures(BitSet sigs) {
      int oldCard = verifiedSignatures.cardinality();
      verifiedSignatures.or(sigs);
      int newCard = verifiedSignatures.cardinality();

      if (newCard > oldCard) {
        if (params.withState) {
          sendStateToPeers();
        }

        if (params.sanFermin) {
          int r = 2;
          while (r < 30 && r < MoreMath.log2(params.signingNodeCount)) {
            BitSet nodesAtRound = sanFerminPeers(r);
            nodesAtRound.and(sigs);
            if (nodesAtRound.length() != 0) {
              // In the sigs we've just verified we have one or more of the sigs of this round
              // We now need to check if we completed the set.
              nodesAtRound = sanFerminPeers(r);
              nodesAtRound.and(verifiedSignatures);
              if (nodesAtRound.equals(sanFerminPeers(r))) {
                // Ok, we're going to contact some of the nodes of the upper level
                //  We're going to select these nodes randomly

                BitSet nextRound = sanFerminPeers(r + 1);
                nextRound.andNot(nodesAtRound);

                // We contact two nodes.
                List<P2PSigNode> dest = randomSubset(nextRound, 2);

                // here we can send:
                // - all the signatures -- good for fault tolerance, bad for message size
                // - only the aggregated signature for this san fermin range
                // - all the signatures we can add on top of this aggregated san fermin sig
                // on the early tests sending all results seems more efficient. But
                // if we suppose that only small messages are supported, then
                //  we can send only the San Fermin ones to make it fit into a UDP message
                // SendSigs ss = new SendSigs(verifiedSignatures,
                // compressedSize(verifiedSignatures));
                SendSigs ss = new SendSigs(sanFerminPeers(r), 1);
                network.send(ss, network.time + 1, this, dest);
              }
            }
            r++;
          }
        }

        if (!done && verifiedSignatures.cardinality() >= params.threshold) {
          doneAt = network.time;
          done = true;
          while (!peersState.isEmpty()) {
            sendSigs();
          }
        }
      }
    }

    private List<P2PSigNode> randomSubset(BitSet nodes, int nodeCt) {
      List<P2PSigNode> res = new ArrayList<>();
      int pos = 0;
      do {
        int cur = nodes.nextSetBit(pos);
        if (cur >= 0) {
          res.add(network.getNodeById(cur));
          pos = cur + 1;
        } else {
          break;
        }
      } while (true);

      for (P2PSigNode n : peers) {
        res.remove(n);
      }

      if (res.size() > nodeCt) {
        Collections.shuffle(res, network.rd);
        return res.subList(0, nodeCt);
      } else {
        return res;
      }
    }

    void sendStateToPeers() {
      State s = new State(this);
      network.send(s, this, peers);
    }

    /** Nothing much to do when we receive a sig set: we just add it to our toVerify list. */
    void onNewSig(BitSet sigs) {
      toVerify.add(sigs);
    }

    /**
     * We select a peer which needs some signatures we have. We also remove it from out list once we
     * sent it a signature set.
     */
    void sendSigs() {
      State found = null;
      BitSet toSend = null;
      Iterator<State> it = peersState.values().iterator();
      while (it.hasNext() && found == null) {
        State cur = it.next();
        toSend = (BitSet) verifiedSignatures.clone();
        toSend.andNot(cur.desc);
        int v1 = toSend.cardinality();

        if (v1 > 0) {
          found = cur;
          it.remove();
        }
      }

      if (!params.withState) {
        found = new State(peers.get(network.rd.nextInt(peers.size())));
      }

      if (found != null) {
        SendSigs ss;
        if (params.sendSigsStrategy == SendSigsStrategy.dif) {
          ss = new SendSigs(toSend);
        } else if (params.sendSigsStrategy == SendSigsStrategy.cmp_all) {
          ss =
              new SendSigs((BitSet) verifiedSignatures.clone(), compressedSize(verifiedSignatures));
        } else if (params.sendSigsStrategy == SendSigsStrategy.cmp_diff) {
          int s1 = compressedSize(verifiedSignatures);
          int s2 = compressedSize(toSend);
          ss = new SendSigs((BitSet) verifiedSignatures.clone(), Math.min(s1, s2));
        } else {
          ss = new SendSigs((BitSet) verifiedSignatures.clone());
        }
        network.send(ss, delayToSend(ss.sigs), this, found.who);
      }
    }

    /**
     * We add a small delay to take into account the message size. This should likely be moved to
     * the framework.
     */
    int delayToSend(BitSet sigs) {
      return network.time + 1 + sigs.cardinality() / 100;
    }

    public void checkSigs() {
      if (params.doubleAggregateStrategy) {
        checkSigs2();
      } else {
        checkSigs1();
      }
    }

    /**
     * Strategy 1: we select the set of signatures which contains the most new signatures. As we
     * send a message to all our peers each time our state change we send more messages with this
     * strategy.
     */
    protected void checkSigs1() {
      BitSet best = null;
      int bestV = 0;
      Iterator<BitSet> it = toVerify.iterator();
      while (it.hasNext()) {
        BitSet o1 = it.next();
        BitSet oo1 = ((BitSet) o1.clone());
        oo1.andNot(verifiedSignatures);
        int v1 = oo1.cardinality();

        if (v1 == 0) {
          it.remove();
        } else {
          if (v1 > bestV) {
            bestV = v1;
            best = o1;
          }
        }
      }

      if (best != null) {
        toVerify.remove(best);
        final BitSet tBest = best;
        network.registerTask(
            () -> P2PSigNode.this.updateVerifiedSignatures(tBest),
            network.time + params.pairingTime * 2,
            P2PSigNode.this);
      }
    }

    /**
     * Strategy 2: we aggregate all signatures together and we test all of them. It's obviously
     * faster, but if someone sent us an invalid signature we have to validate again the signatures.
     * So if we don't need this scheme we should not use it, as it requires to implement a back-up
     * strategy as well.
     */
    protected void checkSigs2() {
      BitSet agg = null;
      for (BitSet o1 : toVerify) {
        if (agg == null) {
          agg = o1;
        } else {
          agg.or(o1);
        }
      }
      toVerify.clear();

      if (agg != null) {
        BitSet oo1 = ((BitSet) agg.clone());
        oo1.andNot(verifiedSignatures);

        if (oo1.cardinality() > 0) {
          // There is at least one signature we don't have yet
          final BitSet tBest = agg;
          network.registerTask(
              () -> P2PSigNode.this.updateVerifiedSignatures(tBest),
              network.time + params.pairingTime * 2,
              P2PSigNode.this);
        }
      }
    }

    @Override
    public String toString() {
      return "P2PSigNode{"
          + "nodeId="
          + nodeId
          + " sendSigsStrategy="
          + params.sendSigsStrategy
          + " sigRange="
          + params.sigRange
          + ", doneAt="
          + doneAt
          + ", sigs="
          + verifiedSignatures.cardinality()
          + ", msgReceived="
          + msgReceived
          + ", msgSent="
          + msgSent
          + ", KBytesSent="
          + bytesSent / 1024
          + ", KBytesReceived="
          + bytesReceived / 1024
          + '}';
    }
  }

  @Override
  public void init() {
    Set<Integer> justRelay = new HashSet<>(params.relayingNodeCount);
    while (justRelay.size() < params.relayingNodeCount) {
      justRelay.add(network.rd.nextInt(params.signingNodeCount + params.relayingNodeCount));
    }

    for (int i = 0; i < params.signingNodeCount + params.relayingNodeCount; i++) {
      final P2PSigNode n = new P2PSigNode(justRelay.contains(i));
      network.addNode(n);
      if (params.withState && !params.sanFermin) {
        network.registerTask(n::sendStateToPeers, 1, n);
      }
      network.registerConditionalTask(
          n::sendSigs, 1, params.sigsSendPeriod, n, () -> !(n.peersState.isEmpty()), () -> !n.done);
      network.registerConditionalTask(
          n::checkSigs, 1, params.pairingTime, n, () -> !n.toVerify.isEmpty(), () -> !n.done);
    }

    if (params.sanFermin) {
      for (int i = 0; i < params.signingNodeCount; i++) {
        final P2PSigNode n = network.getNodeById(i);
        SendSigs sigs = new SendSigs(n.verifiedSignatures);
        int peerId = n.sanFerminPeers(1).length() - 1;
        network.send(sigs, 1, n, network.getNodeById(peerId));
      }
    }

    network.setPeers();
  }

  @Override
  public Network<P2PSigNode> network() {
    return network;
  }

  public static void sigsPerTime() {
    String nl = new NetworkLatency.NetworkLatencyByDistance().getClass().getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    int nodeCt = 300;
    List<Graph.Series> rawResultsMin = new ArrayList<>();
    List<Graph.Series> rawResultsMax = new ArrayList<>();
    List<Graph.Series> rawResultsAvg = new ArrayList<>();

    P2PSignature psTemplate =
        new P2PSignature(
            new P2PSignatureParameters(
                nodeCt,
                nodeCt * 0,
                nodeCt,
                15,
                3,
                50,
                true,
                false,
                SendSigsStrategy.all,
                2,
                nb,
                nl));

    String desc =
        "signingNodeCount="
            + nodeCt
            + (psTemplate.params.sanFermin
                ? ""
                : ", totalNodes="
                    + (psTemplate.params.signingNodeCount + psTemplate.params.relayingNodeCount))
            + ", gossip "
            + (psTemplate.params.sanFermin ? " + San Fermin" : "alone")
            + ", gossip period="
            + psTemplate.params.sigsSendPeriod
            + (!psTemplate.params.sanFermin
                ? ", compression=" + psTemplate.params.sendSigsStrategy
                : "");
    System.out.println(nl + " " + desc);
    Graph graph =
        new Graph(
            "number of signatures per time (" + desc + ")", "time in ms", "number of signatures");
    Graph medianGraph =
        new Graph(
            "average number of signatures per time (" + desc + ")",
            "time in ms",
            "number of signatures");

    int lastSeries = 3;
    StatsHelper.SimpleStats s;

    for (int i = 0; i < lastSeries; i++) {
      Graph.Series curMin = new Graph.Series("signatures count - worse node" + i);
      Graph.Series curMax = new Graph.Series("signatures count - best node" + i);
      Graph.Series curAvg = new Graph.Series("signatures count - average" + i);
      rawResultsAvg.add(curAvg);
      rawResultsMin.add(curMin);
      rawResultsMax.add(curMax);

      P2PSignature ps1 = psTemplate.copy();
      ps1.network.rd.setSeed(i);
      ps1.init();

      do {
        ps1.network.runMs(10);
        s =
            StatsHelper.getStatsOn(
                ps1.network.allNodes, n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
        curMin.addLine(new Graph.ReportLine(ps1.network.time, s.min));
        curMax.addLine(new Graph.ReportLine(ps1.network.time, s.max));
        curAvg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
      } while (s.min != ps1.params.signingNodeCount);
      graph.addSerie(curMin);
      graph.addSerie(curMax);
      graph.addSerie(curAvg);

      System.out.println(
          "bytes sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesSent));
      System.out.println(
          "bytes rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesReceived));
      System.out.println(
          "msg sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgSent));
      System.out.println(
          "msg rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgReceived));
      System.out.println(
          "done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getDoneAt));
    }

    try {
      graph.save(new File("graph_ind.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    Graph.Series seriesAvgmax =
        Graph.statSeries("Signatures count average - best node", rawResultsMax).avg;
    Graph.Series seriesAvgavg =
        Graph.statSeries("Signatures count average - average", rawResultsAvg).avg;
    medianGraph.addSerie(seriesAvgmax);
    medianGraph.addSerie(seriesAvgavg);

    try {
      medianGraph.save(new File("graph_time_avg.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  public P2PSignature copy() {
    return new P2PSignature(params);
  }

  public static void sigsPerStrategy() {
    int nodeCt = 1000;

    String nl = NetworkLatency.NetworkLatencyByDistance.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    P2PSignature ps1 =
        new P2PSignature(
            new P2PSignatureParameters(
                nodeCt, 0, nodeCt, 15, 3, 20, true, false, SendSigsStrategy.all, 1, nb, nl));

    P2PSignature ps2 =
        new P2PSignature(
            new P2PSignatureParameters(
                nodeCt, 0, nodeCt, 15, 3, 20, false, false, SendSigsStrategy.all, 1, nb, nl));

    Graph graph = new Graph("number of sig per time", "time in ms", "sig count");
    Graph.Series series1avg = new Graph.Series("sig count - full aggregate strategy");
    Graph.Series series2avg = new Graph.Series("sig count - single aggregate");
    graph.addSerie(series1avg);
    graph.addSerie(series2avg);

    ps1.init();
    ps2.init();

    StatsHelper.SimpleStats s1;
    StatsHelper.SimpleStats s2;
    do {
      ps1.network.runMs(10);
      ps2.network.runMs(10);
      s1 =
          StatsHelper.getStatsOn(
              ps1.network.allNodes, n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
      s2 =
          StatsHelper.getStatsOn(
              ps2.network.allNodes, n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s1.avg));
      series2avg.addLine(new Graph.ReportLine(ps2.network.time, s2.avg));
    } while (s1.min != nodeCt);

    try {
      graph.save(new File("graph_strat.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  public static void main(String... args) {
    sigsPerTime();
    sigsPerStrategy();
  }
}
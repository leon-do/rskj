package co.rsk.net;

import co.rsk.core.bc.BlockChainStatus;
import co.rsk.net.messages.BlockHashRequestMessage;
import co.rsk.net.messages.BlockHashResponseMessage;
import co.rsk.net.messages.SkeletonRequestMessage;
import org.ethereum.core.Block;
import org.ethereum.core.Blockchain;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by ajlopez on 29/08/2017.
 */
public class SyncProcessor {
    private long nextId;
    private Blockchain blockchain;
    private Map<NodeID, SyncPeerStatus> peers = new HashMap<>();
    private Map<Long, FindPeerStatus> blockHashes = new HashMap<>();

    public SyncProcessor(Blockchain blockchain) {
        this.blockchain = blockchain;
    }

    public int getNoPeers() {
        return this.peers.size();
    }

    public int getNoAdvancedPeers() {
        BlockChainStatus chainStatus = this.blockchain.getStatus();

        if (chainStatus == null)
            return this.peers.size();

        BigInteger totalDifficulty = chainStatus.getTotalDifficulty();
        int count = 0;

        for (SyncPeerStatus peer : this.peers.values())
            if (peer.getStatus().getTotalDifficulty().compareTo(totalDifficulty) > 0)
                count++;

        return count;
    }

    public void processStatus(MessageSender sender, Status status) {
        peers.put(sender.getNodeID(), new SyncPeerStatus(sender, status));
    }

    public void sendSkeletonRequest(MessageSender sender, long height) {
        sender.sendMessage(new SkeletonRequestMessage(++nextId, height));
    }

    public void sendBlockHashRequest(MessageSender sender, long height) {
        sender.sendMessage(new BlockHashRequestMessage(++nextId, height));
    }

    public void findConnectionPoint(MessageSender sender, long height) {
        FindPeerStatus peerStatus = new FindPeerStatus(height, height);
        peerStatus.updateNotFound();
        this.sendBlockHashRequest(sender, peerStatus.getHeight());
        blockHashes.put(nextId, peerStatus);
    }

    public void processBlockHashResponse(MessageSender sender, BlockHashResponseMessage message) {
        FindPeerStatus peerStatus = blockHashes.get(message.getId());
        blockHashes.remove(message.getId());

        Block block = this.blockchain.getBlockByHash(message.getHash());

        if (block != null)
            peerStatus.updateFound();
        else
            peerStatus.updateNotFound();

        if (peerStatus.getFound())
            return;

        sendBlockHashRequest(sender, peerStatus.getHeight());

        blockHashes.put(nextId, peerStatus);
    }

    private static class FindPeerStatus {
        private long height;
        private long interval;
        private boolean found;

        public FindPeerStatus(long height, long interval) {
            this.height = height;
            this.interval = interval;
        }

        public long getHeight() { return this.height; }

        public long getInterval() { return this.interval; }

        public boolean getFound() { return this.found; }

        public void updateFound() {
            if (this.interval == -1) {
                this.found = true;
                return;
            }

            this.interval = Math.abs(this.interval / 2);

            if (this.interval == 0)
                this.interval = 1;

            this.height += this.interval;
        }

        public void updateNotFound() {
            if (this.interval == 1) {
                this.found = true;
                this.height--;
                return;
            }

            this.interval = -Math.abs(this.interval / 2);

            if (this.interval == 0)
                this.interval = -1;

            this.height += this.interval;
        }
    }
}


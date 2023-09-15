package chat;

import io.ipfs.multiaddr.MultiAddress;
import io.ipfs.multihash.Multihash;
import io.libp2p.core.*;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.multiformats.Multiaddr;
import io.netty.buffer.*;
import io.netty.handler.codec.http.*;
import org.peergos.BlockRequestAuthoriser;
import org.peergos.*;
import org.peergos.blockstore.*;
import org.peergos.config.*;
import org.peergos.protocol.dht.*;
import org.peergos.protocol.http.HttpProtocol;
import org.peergos.util.Version;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Chat {
    private EmbeddedIpfs embeddedIpfs;

    private static HttpProtocol.HttpRequestProcessor proxyHandler() {
        return (s, req, h) -> {
            ByteBuf content = req.content();
            String output = content.getCharSequence(0, content.readableBytes(), Charset.defaultCharset()).toString();
            System.out.println("received msg:" + output);
            FullHttpResponse replyOk = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.buffer(0));
            replyOk.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            h.accept(replyOk.retain());
        };
    }
    public Chat() {
        RecordStore recordStore = new RamRecordStore();
        Blockstore blockStore = new RamBlockstore();

        System.out.println("Starting Chat version: " + Version.parse("0.0.1"));
        int portNumber = 10000 + new Random().nextInt(50000);
        List<MultiAddress> swarmAddresses = List.of(new MultiAddress("/ip6/::/tcp/" + portNumber));
        List<MultiAddress> bootstrapNodes = new ArrayList<>(Config.defaultBootstrapNodes);

        HostBuilder builder = new HostBuilder().generateIdentity();
        PrivKey privKey = builder.getPrivateKey();
        PeerId peerId = builder.getPeerId();
        System.out.println("My PeerId:" + peerId.toBase58());
        IdentitySection identitySection = new IdentitySection(privKey.bytes(), peerId);
        BlockRequestAuthoriser authoriser = (c, b, p, a) -> CompletableFuture.completedFuture(true);

        embeddedIpfs = EmbeddedIpfs.build(recordStore, blockStore,
                swarmAddresses,
                bootstrapNodes,
                identitySection,
                authoriser, Optional.of(Chat.proxyHandler()));
        embeddedIpfs.start();
        Thread shutdownHook = new Thread(() -> {
            System.out.println("Stopping server...");
            try {
                embeddedIpfs.stop().join();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        System.out.println("Enter PeerId of other node:");
        Scanner in = new Scanner(System.in);
        String peerIdStr = in.nextLine().trim();
        if (peerIdStr.length() == 0) {
            throw new IllegalArgumentException("Invalid PeerId");
        }
        Multihash targetNodeId = Multihash.fromBase58(peerIdStr);
        PeerId targetPeerId = PeerId.fromBase58(targetNodeId.bareMultihash().toBase58());
        runChat(embeddedIpfs.node, embeddedIpfs.p2pHttp.get(), targetPeerId, getAddresses(targetNodeId));
    }
    private Multiaddr[] getAddresses(Multihash targetNodeId) {
        AddressBook addressBook = embeddedIpfs.node.getAddressBook();
        PeerId targetPeerId = PeerId.fromBase58(targetNodeId.bareMultihash().toBase58());
        Optional<Multiaddr> targetAddressesOpt = addressBook.get(targetPeerId).join().stream().findFirst();
        Multiaddr[] allAddresses = null;
        if (targetAddressesOpt.isEmpty()) {
            List<PeerAddresses> closestPeers = embeddedIpfs.dht.findClosestPeers(targetNodeId, 1, embeddedIpfs.node);
            Optional<PeerAddresses> matching = closestPeers.stream().filter(p -> p.peerId.equals(targetNodeId)).findFirst();
            if (matching.isEmpty()) {
                throw new IllegalStateException("Target not found: " + targetNodeId);
            }
            allAddresses = matching.get().addresses.stream().map(a -> Multiaddr.fromString(a.toString())).toArray(Multiaddr[]::new);
        }
        return targetAddressesOpt.isPresent() ? Arrays.asList(targetAddressesOpt.get()).toArray(Multiaddr[]::new) : allAddresses;
    }
    private void runChat(Host node, HttpProtocol.Binding p2pHttpBinding, PeerId targetPeerId, Multiaddr[] addressesToDial) {
        System.out.println("Type message:");
        Scanner in = new Scanner(System.in);
        while (true) {
            byte[] msg = in.nextLine().trim().getBytes();
            FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.copiedBuffer(msg));
            httpRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, msg.length);
            HttpProtocol.HttpController proxier = p2pHttpBinding.dial(node, targetPeerId, addressesToDial).getController().join();
            proxier.send(httpRequest.retain()).join().release();
        }
    }
    public static void main(String[] args) {
        new Chat();
    }
}

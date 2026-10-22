package fluidic.network;

import fluidic.Fluidic;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Water heights of one 16x16x16 section for client rendering, one byte per cell (255 = full block).
 */
public class MessageWaterSection implements IMessage {

    /** Packets are rejected above this compressed size, a full section deflates to far less */
    private static final int MAX_DATA = 1 << 16;

    private int dimension;
    private long key;
    /** Deflated heights, null when the section has no water */
    private byte[] data;

    public MessageWaterSection() {}

    public MessageWaterSection(int dimension, long key, byte[] data) {
        this.dimension = dimension;
        this.key = key;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        key = buf.readLong();
        int length = buf.readInt();
        if(length > MAX_DATA) throw new IllegalArgumentException("Water section packet too large: " + length);
        if(length > 0) {
            data = new byte[length];
            buf.readBytes(data);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeLong(key);
        buf.writeInt(data == null ? 0 : data.length);
        if(data != null) buf.writeBytes(data);
    }

    /** Inflated heights, null for an empty section */
    private byte[] heights() throws DataFormatException {
        if(data == null) return null;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] heights = new byte[4096];
            int read = 0;
            while(read < heights.length && !inflater.finished()) {
                int count = inflater.inflate(heights, read, heights.length - read);
                if(count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                read += count;
            }
            if(read != heights.length) throw new DataFormatException("Water section has " + read + " cells");
            return heights;
        }
        finally {
            inflater.end();
        }
    }

    public static class Handler implements IMessageHandler<MessageWaterSection, IMessage> {

        @Override
        public IMessage onMessage(MessageWaterSection message, MessageContext ctx) {
            try {
                byte[] heights = message.heights();
                Fluidic.PROXY.handleWaterSection(message.dimension, message.key, heights);
            }
            catch(DataFormatException ex) {
                Fluidic.LOGGER.warn("Discarding corrupt water section packet", ex);
            }
            return null;
        }
    }
}

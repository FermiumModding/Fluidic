package fluidic.network;

import fluidic.block.FluidicWater;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * The dimensions that keep vanilla water on the server, sent to a player when they join. The client draws water,
 * places lily pads and moves boats the vanilla way there.
 */
public class MessageVanillaDimensions implements IMessage {

    /** More ids than any server has, so a broken packet cannot allocate much */
    private static final int MAX_DIMENSIONS = 4096;

    private int[] dimensions;

    public MessageVanillaDimensions() {}

    public MessageVanillaDimensions(int[] dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readInt();
        if(count < 0 || count > MAX_DIMENSIONS) throw new IllegalArgumentException("Too many vanilla water dimensions: " + count);
        dimensions = new int[count];
        for(int n = 0; n < count; n++) dimensions[n] = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimensions.length);
        for(int dimension : dimensions) buf.writeInt(dimension);
    }

    public static class Handler implements IMessageHandler<MessageVanillaDimensions, IMessage> {

        @Override
        public IMessage onMessage(MessageVanillaDimensions message, MessageContext ctx) {
            FluidicWater.clientVanillaDimensions = message.dimensions;
            return null;
        }
    }
}

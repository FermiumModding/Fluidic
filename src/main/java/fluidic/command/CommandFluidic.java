package fluidic.command;

import fluidic.block.FluidicWater;
import fluidic.sim.Reservoirs;
import fluidic.sim.SimManager;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * /fluidic stats, /fluidic inspect x y z, /fluidic add x y z blocks [radius], /fluidic rain x y z blocks
 */
public class CommandFluidic extends CommandBase {

    @Override
    @Nonnull
    public String getName() {
        return "fluidic";
    }

    @Override
    @Nonnull
    public String getUsage(@Nonnull ICommandSender sender) {
        return "/fluidic stats | /fluidic inspect <x> <y> <z> | /fluidic add <x> <y> <z> <blocks> [radius] | /fluidic rain <x> <y> <z> <blocks>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) throws CommandException {
        if(args.length == 0) throw new WrongUsageException(getUsage(sender));
        WaterSimulation simulation = SimManager.get(sender.getEntityWorld());
        if(simulation == null) throw new CommandException("Water simulation is not running in this dimension");

        if(args[0].equals("stats")) {
            sender.sendMessage(new TextComponentString(simulation.stats()));
        }
        else if(args[0].equals("inspect") && args.length >= 4) {
            BlockPos pos = parseBlockPos(sender, args, 1, false);
            World world = sender.getEntityWorld();
            IBlockState state = world.getBlockState(pos);
            sender.sendMessage(new TextComponentString(pos.getX() + " " + pos.getY() + " " + pos.getZ() + ": block " + state
                    + ", sky light " + world.getLightFor(EnumSkyBlock.SKY, pos) + ", block light " + world.getLightFor(EnumSkyBlock.BLOCK, pos)
                    + ", blocks " + state.getLightOpacity(world, pos) + " light, " + simulation.inspect(pos)));
            // Whether water below sea level counts as part of an endless ocean or river, and why
            if(FluidicWater.isWater(state) && pos.getY() < world.getSeaLevel()) {
                sender.sendMessage(new TextComponentString(Reservoirs.describe(world, pos)));
            }
        }
        else if(args[0].equals("rain") && args.length >= 5) {
            // Water the way a raindrop adds it, for testing how rain behaves without waiting for a storm
            BlockPos pos = parseBlockPos(sender, args, 1, false);
            double blocks = parseDouble(args[4], 0.0D, 64.0D);
            simulation.addRain(pos, (int)Math.round(blocks * Water.FULL));
        }
        else if(args[0].equals("add") && args.length >= 5) {
            BlockPos center = parseBlockPos(sender, args, 1, false);
            double blocks = parseDouble(args[4], 0.0D, 100000.0D);
            int radius = args.length >= 6 ? parseInt(args[5], 0, 32) : 0;
            int columns = (2 * radius + 1) * (2 * radius + 1);
            int amount = (int)Math.round(blocks * Water.FULL / columns);
            for(int dx = -radius; dx <= radius; dx++) {
                for(int dz = -radius; dz <= radius; dz++) {
                    simulation.addWater(center.add(dx, 0, dz), amount);
                }
            }
            notifyCommandListener(sender, this, "Added %s blocks of water at %s", blocks, center.getX() + " " + center.getY() + " " + center.getZ());
        }
        else throw new WrongUsageException(getUsage(sender));
    }

    @Override
    @Nonnull
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args, @Nullable BlockPos targetPos) {
        if(args.length == 1) return getListOfStringsMatchingLastWord(args, "stats", "inspect", "add", "rain");
        if(args.length >= 2 && args.length <= 4 && !args[0].equals("stats")) return getTabCompletionCoordinate(args, 1, targetPos);
        return Collections.emptyList();
    }
}

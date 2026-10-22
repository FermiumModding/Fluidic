package fluidic.client;

import fluidic.Fluidic;
import fluidic.sim.SimulatedWater;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Vanilla's underwater fog when the camera is inside water that is still being simulated. The blue overlay
 * already appears, since the entity mixin makes Entity.isInsideOfMaterial see simulated water.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public final class UnderwaterView {

    private UnderwaterView() {}

    private static boolean inSimulatedWater(EntityViewRenderEvent event) {
        if(event.getState().getMaterial() == Material.WATER) return false;
        Entity entity = event.getEntity();
        Vec3d eyes = ActiveRenderInfo.projectViewFromEntity(entity, event.getRenderPartialTicks());
        return SimulatedWater.isSubmerged(entity.world, eyes.x, eyes.y, eyes.z);
    }

    @SubscribeEvent
    public static void onFogDensity(EntityViewRenderEvent.FogDensity event) {
        if(!inSimulatedWater(event)) return;
        Entity entity = event.getEntity();
        float density = 0.1F;
        if(entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase)entity;
            density = living.isPotionActive(MobEffects.WATER_BREATHING) ? 0.01F : 0.1F - EnchantmentHelper.getRespirationModifier(living) * 0.03F;
        }
        GlStateManager.setFog(GlStateManager.FogMode.EXP);
        event.setDensity(density);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColors(EntityViewRenderEvent.FogColors event) {
        if(!inSimulatedWater(event)) return;
        Entity entity = event.getEntity();
        float brighten = 0.0F;
        if(entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase)entity;
            brighten = EnchantmentHelper.getRespirationModifier(living) * 0.2F;
            if(living.isPotionActive(MobEffects.WATER_BREATHING)) brighten = brighten * 0.3F + 0.6F;
        }
        event.setRed(0.02F + brighten);
        event.setGreen(0.02F + brighten);
        event.setBlue(0.2F + brighten);
    }
}

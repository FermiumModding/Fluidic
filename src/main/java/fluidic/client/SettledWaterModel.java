package fluidic.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.vecmath.Matrix4f;
import java.util.ArrayList;
import java.util.List;

/**
 * Forge's fluid model, rendered the way vanilla renders water: without ambient occlusion, which darkens it
 * along shores, and tinted with the biome water color.
 */
public class SettledWaterModel implements IBakedModel {

    private final IBakedModel parent;

    public SettledWaterModel(IBakedModel parent) {
        this.parent = parent;
    }

    @Override
    @Nonnull
    public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) {
        List<BakedQuad> quads = parent.getQuads(state, side, rand);
        List<BakedQuad> tinted = new ArrayList<>(quads.size());
        for(BakedQuad quad : quads) {
            tinted.add(new BakedQuad(quad.getVertexData(), 0, quad.getFace(), quad.getSprite(), quad.shouldApplyDiffuseLighting(), quad.getFormat()));
        }
        return tinted;
    }

    @Override
    public boolean isAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isAmbientOcclusion(IBlockState state) {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return parent.isGui3d();
    }

    @Override
    public boolean isBuiltInRenderer() {
        return parent.isBuiltInRenderer();
    }

    @Override
    @Nonnull
    public TextureAtlasSprite getParticleTexture() {
        return parent.getParticleTexture();
    }

    @Override
    @Nonnull
    public ItemCameraTransforms getItemCameraTransforms() {
        return parent.getItemCameraTransforms();
    }

    @Override
    @Nonnull
    public ItemOverrideList getOverrides() {
        return parent.getOverrides();
    }

    @Override
    @Nonnull
    public Pair<? extends IBakedModel, Matrix4f> handlePerspective(@Nonnull ItemCameraTransforms.TransformType type) {
        return parent.handlePerspective(type);
    }
}

package mirefresh.mir.mi.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a host block's baked model and appends the (already-baked, already-oriented) connector
 * quads. Everything except quad output is delegated to the wrapped model, so the host keeps its
 * own particle, transforms, render types, ambient occlusion, etc.
 *
 * <p>The extra quads are only emitted on the {@code side == null} (non-culled) pass so they always
 * render regardless of the host's culling.
 */
public class ConnectorModelWrapper implements BakedModel {

    private final BakedModel wrapped;
    private final List<BakedQuad> connectorQuads;

    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> connectorQuads) {
        this.wrapped = wrapped;
        this.connectorQuads = connectorQuads;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        List<BakedQuad> base = wrapped.getQuads(state, side, rand);
        if (side != null) return base;
        List<BakedQuad> out = new ArrayList<>(base.size() + connectorQuads.size());
        out.addAll(base);
        out.addAll(connectorQuads);
        return out;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
                                             @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = wrapped.getQuads(state, side, rand, data, renderType);
        if (side != null) return base;
        List<BakedQuad> out = new ArrayList<>(base.size() + connectorQuads.size());
        out.addAll(base);
        out.addAll(connectorQuads);
        return out;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        return wrapped.getRenderTypes(state, rand, data);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull net.minecraft.world.level.BlockAndTintGetter level,
                                           @NotNull net.minecraft.core.BlockPos pos, @NotNull BlockState state, @NotNull ModelData modelData) {
        return wrapped.getModelData(level, pos, state, modelData);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return wrapped.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return wrapped.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return wrapped.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return wrapped.isCustomRenderer();
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull TextureAtlasSprite getParticleIcon() {
        return wrapped.getParticleIcon();
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        return wrapped.getParticleIcon(data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull ItemTransforms getTransforms() {
        return wrapped.getTransforms();
    }

    @Override
    public @NotNull ItemOverrides getOverrides() {
        return wrapped.getOverrides();
    }
}

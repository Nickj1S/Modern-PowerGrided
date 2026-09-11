package mirefresh.mir.mi.client;

import aztech.modern_industrialization.machines.models.MachineModelClientData;
import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a MI machine's baked model and appends the connector geometry, rotated onto the face
 * opposite the machine's front (read from {@link MachineModelClientData#frontDirection} in the
 * per-block {@link ModelData}). Everything except quad output is delegated to the host model.
 *
 * <p>Extra quads are emitted only on the {@code side == null} (non-culled) pass so they always
 * render regardless of the host's culling.
 */
public class ConnectorModelWrapper implements BakedModel {

    private final BakedModel wrapped;
    /** Connector quads as authored (canonical face = +Z / south). */
    private final List<BakedQuad> canonicalQuads;
    /** Lazily-built per-face rotations of {@link #canonicalQuads}. */
    private final Map<Direction, List<BakedQuad>> rotated = new EnumMap<>(Direction.class);

    public ConnectorModelWrapper(BakedModel wrapped, List<BakedQuad> canonicalQuads) {
        this.wrapped = wrapped;
        this.canonicalQuads = canonicalQuads;
    }

    /**
     * Resolve which block face the connector sits on. Storage units / hatches have no "front",
     * only an {@link MachineModelClientData#outputDirection} (wrench-configurable) — that wins.
     * Plain machines fall back to the face opposite their {@link MachineModelClientData#frontDirection}.
     */
    private static Direction faceFor(@Nullable MachineModelClientData mi) {
        if (mi != null && mi.outputDirection != null) return mi.outputDirection;
        if (mi != null && mi.frontDirection != null) return mi.frontDirection.getOpposite();
        return Direction.SOUTH; // canonical (also the dataless / item path)
    }

    private List<BakedQuad> quadsForFace(Direction face) {
        return rotated.computeIfAbsent(face, f -> {
            if (f == Direction.SOUTH) return canonicalQuads; // canonical
            Transformation t = rotationTo(f);
            return QuadTransformers.applying(t).process(canonicalQuads);
        });
    }

    /** Rotation that carries the canonical +Z geometry onto {@code face}, about the block centre. */
    private static Transformation rotationTo(Direction face) {
        // Angles chosen to match MiIntegration#terminal (TerminalBoundingBox.rotateAroundY/X), so the
        // clickable box and the visible post land on the same face for all six orientations.
        var rot = switch (face) {
            case SOUTH -> Axis.YP.rotationDegrees(0);
            case WEST  -> Axis.YP.rotationDegrees(270);
            case NORTH -> Axis.YP.rotationDegrees(180);
            case EAST  -> Axis.YP.rotationDegrees(90);
            case UP    -> Axis.XP.rotationDegrees(-90);
            case DOWN  -> Axis.XP.rotationDegrees(90);
        };
        return new Transformation(new Vector3f(0.5f, 0.5f, 0.5f), rot, null, null)
                .compose(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f), null, null, null));
    }

    private List<BakedQuad> combine(List<BakedQuad> base, Direction face) {
        List<BakedQuad> extra = quadsForFace(face);
        List<BakedQuad> out = new ArrayList<>(base.size() + extra.size());
        out.addAll(base);
        out.addAll(extra);
        return out;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
        List<BakedQuad> base = wrapped.getQuads(state, side, rand);
        return side != null ? base : combine(base, Direction.SOUTH);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
                                             @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = wrapped.getQuads(state, side, rand, data, renderType);
        if (side != null) return base;
        return combine(base, faceFor(data.get(MachineModelClientData.KEY)));
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        return wrapped.getRenderTypes(state, rand, data);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos,
                                           @NotNull BlockState state, @NotNull ModelData modelData) {
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

package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import mirefresh.mir.Mir;
import mirefresh.mir.mi.MiElectricHolder;
import mirefresh.mir.mi.client.ConnectorModelWrapper;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only counterpart to {@code MachineBlockEntityMixin}: exposes
 * {@link MiElectricHolder#mir$connectorFace()} to the render/model-baking side by riding MI's own
 * per-block {@link ModelData}, since the connector's face is no longer derivable from MI's synced
 * {@code MachineModelClientData} once an edge-zone wrench click has moved it independently. Split
 * out of the common mixin because {@code ModelData}/{@code ModelProperty} are client-only types.
 */
@Mixin(MachineBlockEntity.class)
public abstract class MachineBlockEntityClientMixin {

    // TEMP: rate-limited diagnostic log, only fires when a given block position's rendered placement
    // actually changes, to confirm the render side receives the same value the click set.
    private static final Map<BlockPos, ConnectorModelWrapper.Placement> mir$lastLoggedPlacement = new ConcurrentHashMap<>();

    @Inject(method = "getModelData()Lnet/neoforged/neoforge/client/model/data/ModelData;",
            at = @At("RETURN"), cancellable = true)
    private void mir$injectConnectorFace(CallbackInfoReturnable<ModelData> cir) {
        MiElectricHolder holder = (MiElectricHolder) (Object) this;
        if (!holder.mir$electrified()) return;
        ConnectorModelWrapper.Placement placement =
                new ConnectorModelWrapper.Placement(holder.mir$connectorFace(), holder.mir$connectorEdge());
        BlockPos pos = ((MachineBlockEntity) (Object) this).getBlockPos();
        if (!placement.equals(mir$lastLoggedPlacement.put(pos, placement))) {
            Mir.LOGGER.info("[mir] render getModelData: pos={}, primary={}, secondary={}",
                    pos, placement.primary(), placement.secondary());
        }
        ModelData withFace = cir.getReturnValue().derive()
                .with(ConnectorModelWrapper.CONNECTOR_FACE, placement)
                .build();
        cir.setReturnValue(withFace);
    }
}

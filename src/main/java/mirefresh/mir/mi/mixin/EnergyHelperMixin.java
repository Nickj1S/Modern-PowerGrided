package mirefresh.mir.mi.mixin;

import aztech.modern_industrialization.api.energy.CableTier;
import aztech.modern_industrialization.api.energy.MIEnergyStorage;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.components.OrientationComponent;
import aztech.modern_industrialization.machines.helper.EnergyHelper;
import mirefresh.mir.mi.MiElectricHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A storage unit's {@code outputDirection} used to mean two things at once: which side our
 * connector sits on, <em>and</em> which side MI itself auto-pushes EU out of via a physical MI
 * cable ({@code AbstractStorageMachineBlockEntity#tick} calling this exact overload — and
 * {@code GeneratorMachineBlockEntity#tick} the same, for generators). Once a block is electrified
 * the connector is the only intended energy path, so this overload — the one that pushes onto
 * {@code orientation.outputDirection} specifically — is skipped for both buffers and generators.
 * The sided overload (used for the non-output faces) is untouched, and non-electrified blocks
 * (anything not yet a target) keep pushing exactly as MI always has.
 */
@Mixin(EnergyHelper.class)
public abstract class EnergyHelperMixin {

    @Inject(method = "autoOutput(Laztech/modern_industrialization/machines/MachineBlockEntity;"
            + "Laztech/modern_industrialization/machines/components/OrientationComponent;"
            + "Laztech/modern_industrialization/api/energy/CableTier;"
            + "Laztech/modern_industrialization/api/energy/MIEnergyStorage;)V", at = @At("HEAD"), cancellable = true)
    private static void mir$skipOnElectrifiedOutput(MachineBlockEntity machine, OrientationComponent orientation,
            CableTier output, MIEnergyStorage energySource, CallbackInfo ci) {
        if (machine instanceof MiElectricHolder holder && (holder.mir$isBuffer() || holder.mir$isGenerator())) {
            ci.cancel();
        }
    }
}

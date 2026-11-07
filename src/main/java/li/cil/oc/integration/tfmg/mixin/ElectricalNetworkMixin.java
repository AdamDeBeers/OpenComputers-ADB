package li.cil.oc.integration.tfmg.mixin;

import com.drmangotea.tfmg.content.electricity.base.ElectricalNetwork;
import com.drmangotea.tfmg.content.electricity.base.VoltageAlteringBlockEntity;
import com.drmangotea.tfmg.content.electricity.network.large_switch.LargeSwitchBlockEntity;
import com.drmangotea.tfmg.content.electricity.network.transformer.large.LargeTransformerBlockEntity;
import com.drmangotea.tfmg.content.electricity.utilities.electric_motor.ElectricMotorBlockEntity;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TFMG's {@code ElectricalNetwork#handleInsufficientPower()} re-flags every member as
 * undersupplied and re-triggers their update cascade on every single call, even while the
 * network stays continuously undersupplied. With a heavy cable hub this repeats every tick.
 *
 * The upstream method body is left fully intact: the undersupply check and the per-member
 * {@code notEnoughPower} / {@code tickUntilConnectFE} writes still run on every call. Only the
 * cascade (motor rotation update and the {@code updateInFront} flags) is gated, so it fires
 * on the transition into the undersupplied state instead of on every call.
 *
 * The undersupplied state is observed rather than re-computed: upstream only writes
 * {@code notEnoughPower} inside the loop it enters when the network is undersupplied, so a
 * write to that field during a call means the network was undersupplied on that call.
 */
@Mixin(ElectricalNetwork.class)
abstract class ElectricalNetworkMixin {
   // Whether the previous completed call found the network undersupplied.
   @Unique
   private boolean opencomputers$wasUndersupplied = false;

   // Whether the current call has found the network undersupplied so far.
   @Unique
   private boolean opencomputers$undersuppliedThisCall = false;

   @Inject(method = "handleInsufficientPower", at = @At("HEAD"), remap = false)
   private void opencomputers$beginCall(CallbackInfo ci) {
      this.opencomputers$undersuppliedThisCall = false;
   }

   @Inject(
      method = "handleInsufficientPower",
      at = @At(
         value = "FIELD",
         target = "Lcom/drmangotea/tfmg/content/electricity/base/ElectricBlockValues;notEnoughPower:Z",
         opcode = Opcodes.PUTFIELD,
         remap = false
      ),
      remap = false
   )
   private void opencomputers$markUndersupplied(CallbackInfo ci) {
      this.opencomputers$undersuppliedThisCall = true;
   }

   @Inject(method = "handleInsufficientPower", at = @At("RETURN"), remap = false)
   private void opencomputers$endCall(CallbackInfo ci) {
      this.opencomputers$wasUndersupplied = this.opencomputers$undersuppliedThisCall;
   }

   // The cascade may only run when the previous call was not already undersupplied.
   // opencomputers$wasUndersupplied is only updated at RETURN, so it holds the previous
   // call's state for the whole loop.
   @Unique
   private boolean opencomputers$shouldCascade() {
      return !this.opencomputers$wasUndersupplied;
   }

   @WrapWithCondition(
      method = "handleInsufficientPower",
      at = @At(
         value = "INVOKE",
         target = "Lcom/drmangotea/tfmg/content/electricity/utilities/electric_motor/ElectricMotorBlockEntity;updateGeneratedRotation()V",
         remap = false
      ),
      remap = false
   )
   private boolean opencomputers$gateMotorUpdate(ElectricMotorBlockEntity motor) {
      return this.opencomputers$shouldCascade();
   }

   @WrapWithCondition(
      method = "handleInsufficientPower",
      at = @At(
         value = "FIELD",
         target = "Lcom/drmangotea/tfmg/content/electricity/base/VoltageAlteringBlockEntity;updateInFront:Z",
         opcode = Opcodes.PUTFIELD,
         remap = false
      ),
      remap = false
   )
   private boolean opencomputers$gateVoltageAlteringUpdate(VoltageAlteringBlockEntity be, boolean value) {
      return this.opencomputers$shouldCascade();
   }

   @WrapWithCondition(
      method = "handleInsufficientPower",
      at = @At(
         value = "FIELD",
         target = "Lcom/drmangotea/tfmg/content/electricity/network/large_switch/LargeSwitchBlockEntity;updateInFront:Z",
         opcode = Opcodes.PUTFIELD,
         remap = false
      ),
      remap = false
   )
   private boolean opencomputers$gateLargeSwitchUpdate(LargeSwitchBlockEntity be, boolean value) {
      return this.opencomputers$shouldCascade();
   }

   @WrapWithCondition(
      method = "handleInsufficientPower",
      at = @At(
         value = "FIELD",
         target = "Lcom/drmangotea/tfmg/content/electricity/network/transformer/large/LargeTransformerBlockEntity;updateInFront:Z",
         opcode = Opcodes.PUTFIELD,
         remap = false
      ),
      remap = false
   )
   private boolean opencomputers$gateLargeTransformerUpdate(LargeTransformerBlockEntity be, boolean value) {
      return this.opencomputers$shouldCascade();
   }
}

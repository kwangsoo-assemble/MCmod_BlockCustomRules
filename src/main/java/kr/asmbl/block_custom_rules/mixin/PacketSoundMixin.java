package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kr.asmbl.block_custom_rules.sound.HeldBlock;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 서버 소리 패킷 표시(D13 · D18) — 처리하는 동안 «패킷에서 왔다» 를 걸어 둔다(출처 · 표식 시드 거르기 · 발밑 블록 찾기).
 * 네트워크 스레드에서 처음 불리면 바닐라가 주 스레드로 다시 넘긴다 — 표시는 스레드마다라 섞이지 않는다.
 */
@Mixin(ClientPacketListener.class)
public abstract class PacketSoundMixin {
	@WrapMethod(method = "handleSoundEvent")
	private void bcr$markPacket(ClientboundSoundPacket packet, Operation<Void> original) {
		Boolean prev = HeldBlock.enterPacket();
		try {
			original.call(packet);
		} finally {
			HeldBlock.exitPacket(prev);
		}
	}
}

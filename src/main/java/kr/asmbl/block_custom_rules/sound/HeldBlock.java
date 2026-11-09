package kr.asmbl.block_custom_rules.sound;

import net.minecraft.world.level.block.state.BlockState;

/**
 * «지금 소리를 내는 블록» 표시(D17) — 블록을 들고 소리를 부르는 자리(걸음 · 떨어짐 · 놓기 · 부수기)가 그 동안 걸어 둔다.
 * 스레드마다 따로라 혼자 하기의 내장 서버 스레드와 섞이지 않는다. 걸 때 앞 값을 받아 두고 끝에 되돌린다(겹쳐 불려도 된다).
 * 패킷 표시도 같은 꼴 — 지금 서버 소리 패킷을 처리하는 중인가.
 */
public final class HeldBlock {
	private static final ThreadLocal<BlockState> CURRENT = new ThreadLocal<>();
	private static final ThreadLocal<Boolean> PACKET = new ThreadLocal<>();

	private HeldBlock() {
	}

	public static BlockState get() {
		return CURRENT.get();
	}

	/** 걸고 앞 값을 돌려준다 — 끝에 {@link #restore} 로 되돌린다. */
	public static BlockState swap(BlockState state) {
		BlockState prev = CURRENT.get();
		CURRENT.set(state);
		return prev;
	}

	public static void restore(BlockState prev) {
		CURRENT.set(prev);
	}

	public static boolean inPacket() {
		return Boolean.TRUE.equals(PACKET.get());
	}

	public static Boolean enterPacket() {
		Boolean prev = PACKET.get();
		PACKET.set(Boolean.TRUE);
		return prev;
	}

	public static void exitPacket(Boolean prev) {
		PACKET.set(prev);
	}
}

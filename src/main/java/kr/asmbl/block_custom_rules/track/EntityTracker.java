package kr.asmbl.block_custom_rules.track;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.function.Consumer;
import kr.asmbl.block_custom_rules.world.Generation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 엔티티 추적기(D36) — 틱마다 한 번 카메라 16칸 안 살아 있는 엔티티를 모은다. 발소리 · 휘기가 같이 쓴다.
 * 엔티티는 id 와 세대로만 든다(D40) — 쓸 때 월드에서 다시 찾고 없으면 건너뛴다. 세대가 바뀌면 비운다.
 */
public final class EntityTracker {
	public static final double RANGE = 16.0;
	private static final IntArrayList IDS = new IntArrayList();
	private static int generation = -1;

	private EntityTracker() {
	}

	public static void reset() {
		IDS.clear();
		generation = -1;
	}

	/** 클라 틱 끝 — 다시 모은다. */
	public static void tick(Minecraft mc) {
		IDS.clear();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		for (Entity e : level.getEntities((Entity) null, new AABB(cam, cam).inflate(RANGE), Entity::isAlive)) {
			IDS.add(e.getId());
		}
		generation = Generation.current();
	}

	/** 모아 둔 엔티티 중 지금 월드에 있는 것마다 — 세대가 다르면 아무것도 안 한다. */
	public static void forEach(ClientLevel level, Consumer<Entity> each) {
		if (level == null || generation != Generation.current()) {
			return;
		}
		for (int i = 0; i < IDS.size(); i++) {
			Entity e = level.getEntity(IDS.getInt(i));
			if (e != null && e.isAlive()) {
				each.accept(e);
			}
		}
	}

	public static int size() {
		return IDS.size();
	}
}

package kr.asmbl.block_custom_rules.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 엔티티 하나의 걸음 판정(D25 · D26) — 틱마다 위치 · 상태를 받아 «무슨 행동을 몇 발로» 를 낸다. 마인크래프트 클래스를 모른다.
 * 간격은 «걸은 칸 × 0.6»(바닐라 `moveDist` 단위)을 쌓아 넘을 때마다: 걷기 0.95 · 계단 오름 0.6175 · 사다리 0.5 · 계단 내림은 바로.
 * 넘친 만큼은 다음 걸음으로 넘긴다 — 평균 간격이 바닐라(1 · 약 1.67칸)의 0.95배(약 1.58칸)가 된다.
 * 4틱(200ms) 넘게 멈추면 쌓인 거리를 버리고, 조금이라도 걸었으면 `wander` 한 발.
 * 착지: 떨어진 높이 0.9 넘으면 `land` · 0.2 넘으면 `land_light`(두 발) · 점프는 내 플레이어만(남의 점프는 동기화가 안 온다).
 * 웅크림이면 행동 이름 끝에 `_sneak`.
 */
public final class StepTracker {
	public static final double STRIDE = 0.95;
	public static final double STAIR = 0.6175;
	public static final double LADDER = 0.5;
	public static final double STAIR_HEIGHT = 0.4;
	public static final double LAND_HEAVY = 0.9;
	public static final double LAND_LIGHT = 0.2;
	public static final int STILL_TICKS = 4;
	public static final double WANDER_MIN = 0.25;
	public static final double JUMP_DY = 0.1;
	/** 제자리 점프로 보는 가로 속도(칸/틱) — 이보다 느리면 `wander` 두 발 · 빠르면 `jump` 한 발. */
	public static final double JUMP_IN_PLACE = 0.05;

	/** 엔진 기본 행동 부모(D20) — 걸을 때 · 달릴 때 둘(`land_light` · 계단은 속도 따라 `run` · `step`). 빈 글자 = 부모 없음. */
	public static final Map<String, String> PARENTS_WALK = parents("step");
	public static final Map<String, String> PARENTS_RUN = parents("run");

	private static Map<String, String> parents(String moving) {
		Map<String, String> m = new java.util.HashMap<>();
		m.put("run", "step");
		m.put("wander", "step");
		m.put("jump", "wander");
		m.put("land", "step");
		m.put("land_light", moving);
		m.put("climb", "step");
		m.put("stairs_up", moving);
		m.put("stairs_down", moving);
		m.put("crawl", "step");
		for (String a : new String[] { "step", "run", "wander", "land_light", "stairs_up", "stairs_down", "crawl", "through", "enter", "exit" }) {
			m.put(a + "_sneak", "");
		}
		m.put("climb_sneak", "climb");
		m.put("jump_sneak", "jump");
		m.put("land_sneak", "land");
		return Map.copyOf(m);
	}

	/** 한 틱의 재료. */
	public record Input(double x, double y, double z, boolean onGround, boolean climbing, boolean sprinting, boolean sneaking, boolean crawling,
			boolean local) {
	}

	/** 낼 소리 — 행동(웅크림 붙은 이름) · 발 수(1 = 다음 발 · 2 = 두 발 같이) · 달리는 중(부모 고르기) · 발밑을 잡을 높이(점프는 뛰기 직전 높이). */
	public record Event(String action, int feet, boolean running, double y) {
	}

	private boolean init;
	private double lx;
	private double ly;
	private double lz;
	private double acc;
	private double stepY;
	private double airTop;
	private boolean grounded;
	private int still;
	private boolean rightFoot;

	/** 다음 한 발이 오른발인가 — 부를 때마다 바뀐다. */
	public boolean nextFoot() {
		this.rightFoot = !this.rightFoot;
		return this.rightFoot;
	}

	public double accumulated() {
		return this.acc;
	}

	public List<Event> tick(Input in) {
		List<Event> out = new ArrayList<>(2);
		if (!this.init) {
			this.init = true;
			this.lx = in.x;
			this.ly = in.y;
			this.lz = in.z;
			this.stepY = in.y;
			this.airTop = in.y;
			this.grounded = in.onGround || in.climbing;
			return out;
		}
		double dx = in.x - this.lx;
		double prevY = this.ly;
		double dy = in.y - this.ly;
		double dz = in.z - this.lz;
		this.lx = in.x;
		this.ly = in.y;
		this.lz = in.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		boolean nowGrounded = in.onGround || in.climbing;
		String sneak = in.sneaking ? "_sneak" : "";

		if (!nowGrounded) {
			if (this.grounded) {
				this.airTop = in.y;
				if (in.local && dy > JUMP_DY) {
					out.add(horiz < JUMP_IN_PLACE ? new Event("wander" + sneak, 2, in.sprinting, prevY) : new Event("jump" + sneak, 1, in.sprinting, prevY));
				}
			}
			this.airTop = Math.max(this.airTop, in.y);
			this.grounded = false;
			this.still = 0;
			return out;
		}
		if (!this.grounded) {
			double drop = this.airTop - in.y;
			if (drop > LAND_HEAVY) {
				out.add(new Event("land" + sneak, 2, in.sprinting, in.y));
			} else if (drop > LAND_LIGHT) {
				out.add(new Event("land_light" + sneak, 2, in.sprinting, in.y));
			}
			this.grounded = true;
			this.acc = 0.0;
			this.stepY = in.y;
			this.still = 0;
			return out;
		}

		double moved = horiz + (in.climbing ? Math.abs(dy) : 0.0);
		this.acc += moved * 0.6;
		boolean onLadder = in.climbing && !in.onGround;
		double rise = in.y - this.stepY;
		if (!onLadder && rise < -STAIR_HEIGHT) {
			out.add(new Event("stairs_down" + sneak, 1, in.sprinting, in.y));
			this.acc = 0.0;
			this.stepY = in.y;
		} else {
			boolean up = !onLadder && rise > STAIR_HEIGHT;
			double interval = onLadder ? LADDER : up ? STAIR : STRIDE;
			if (this.acc >= interval) {
				out.add(new Event((up ? "stairs_up" : base(in, onLadder)) + sneak, 1, in.sprinting, in.y));
				// 넘친 거리는 다음 걸음으로 넘긴다(평균 간격이 0.95 가 되게) · 순간 이동처럼 크게 넘치면 버린다
				this.acc -= interval;
				if (this.acc >= interval) {
					this.acc = 0.0;
				}
				this.stepY = in.y;
			}
		}
		if (moved < 1.0e-4) {
			this.still++;
			if (this.still == STILL_TICKS) {
				if (this.acc >= WANDER_MIN) {
					out.add(new Event("wander" + sneak, 1, in.sprinting, in.y));
				}
				this.acc = 0.0;
				this.stepY = in.y;
			}
		} else {
			this.still = 0;
		}
		return out;
	}

	private static String base(Input in, boolean onLadder) {
		if (onLadder) {
			return "climb";
		}
		if (in.crawling) {
			return "crawl";
		}
		return in.sprinting ? "run" : "step";
	}

	/** 행동 이름이 웅크림 변형인가. */
	public static boolean isSneak(String action) {
		return action.endsWith("_sneak");
	}

	/** 바닐라답게 낼 때의 음량 배율(«끝내 없을 때» — `wander` 는 반). */
	public static float vanillaFactor(String action) {
		return action.startsWith("wander") ? 0.5F : 1.0F;
	}

	/** 발 자리 — 몸 방향 좌우로 0.2 × 크기(PF 꼴). {dx, dz} */
	public static double[] footOffset(float bodyYawDeg, boolean right, double size) {
		double rot = Math.toRadians(bodyYawDeg);
		double d = 0.2 * size * (right ? -1.0 : 1.0);
		return new double[] { Math.cos(rot) * d, Math.sin(rot) * d };
	}
}

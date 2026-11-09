package kr.asmbl.block_custom_rules.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 엔티티 하나의 걸음 판정(D25 · D26) — 틱마다 위치 · 상태를 받아 «무슨 행동을 몇 발로» 를 낸다. 마인크래프트 클래스를 모른다.
 * 간격은 «걸은 칸 × 0.6»(바닐라 `moveDist` 단위)을 쌓아 넘을 때마다: 걷기 0.95 · 계단 오름 0.6175 · 사다리 0.5 · 계단 내림은 바로.
 * 넘친 만큼은 다음 걸음으로 넘긴다 — 평균 간격이 바닐라(1 · 약 1.67칸)의 0.95배(약 1.58칸)가 된다.
 * 꺾기 · 멈춤: 이번 틱 · 지난 틱 가로 움직임의 내적이 0.001 아래로 떨어지는 순간(PF — 멈춤 · 한 틱 안 급히 꺾기) 또는
 * 최근 5틱 안의 움직임과 65° 넘게 어긋날 때(가속 · 마찰로 2 ~ 4틱에 걸쳐 도는 꺾기) `wander` 한 발 · 꺾기 뒤 8틱은 다시 안 낸다.
 * 4틱(200ms) 넘게 멈추면 쌓인 거리를 버린다.
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
	/** 꺾기 문턱 — 이번 · 지난 틱 가로 움직임(칸/틱)의 내적(PF 0.001). */
	public static final double TURN_DOT = 0.001;
	/** 꺾기 각도 문턱 — cos 65° · 이보다 많이 어긋나면 꺾기. */
	public static final double TURN_COS = Math.cos(Math.toRadians(65.0));
	/** 꺾기로 볼 가장 느린 가로 빠르기(칸/틱) — 웅크려 걷기(약 0.065)도 든다. */
	public static final double TURN_SPEED = 0.05;
	public static final int TURN_WINDOW = 5;
	public static final int TURN_COOLDOWN = 8;
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

	/**
	 * 한 틱의 재료.
	 *
	 * @param stride    몹 고유 간격(바닐라 `nextStep` — 보통 1 · 거북 0.15 · 스트라이더 0.6 · 워든 0.55) — 0.95 를 곱한다
	 * @param quadruped 네 발(PF 3박 · 같은 틱 한 발 더 — `entities` 규칙 `locomotion: quadruped` 일 때만)
	 * @param size      크기 배율 f = (배율 − 1) × 0.6 + 1 — 간격에 곱한다(`size_scaling` 일 때만 · 아니면 1)
	 */
	public record Input(double x, double y, double z, boolean onGround, boolean climbing, boolean sprinting, boolean sneaking, boolean crawling,
			boolean local, double stride, boolean quadruped, double size) {
		public Input(double x, double y, double z, boolean onGround, boolean climbing, boolean sprinting, boolean sneaking, boolean crawling, boolean local) {
			this(x, y, z, onGround, climbing, sprinting, sneaking, crawling, local, 1.0, false, 1.0);
		}
	}

	/** 크기 배율(D25) — 엔티티 배율 s → (s − 1) × 0.6 + 1. 간격에 곱하고 높낮이는 이것으로 나눈다. */
	public static double sizeFactor(double scale) {
		return (scale - 1.0) * 0.6 + 1.0;
	}

	/** 낼 소리 — 행동(웅크림 붙은 이름) · 발 수(1 = 다음 발 · 2 = 두 발 같이) · 달리는 중(부모 고르기) · 발밑을 잡을 높이(점프는 뛰기 직전 높이). */
	public record Event(String action, int feet, boolean running, double y) {
	}

	private final java.util.function.DoubleSupplier rnd;
	private boolean init;
	/** 네 발 박자 — 발굽 차례(0 ~ 2) · 다음 걸음 거리 배율 씨앗. */
	private int hoof;
	private double hoofSeed = 0.05;
	/** 정지(stand) — 멈춘 채 남은 틱(0.5 ~ 3초 무작위). */
	private int standTicks = -1;

	public StepTracker() {
		this(Math::random);
	}

	public StepTracker(java.util.function.DoubleSupplier rnd) {
		this.rnd = rnd;
	}
	private double lx;
	private double ly;
	private double lz;
	private double acc;
	private double stepY;
	private double airTop;
	private boolean grounded;
	private int still;
	private boolean rightFoot;
	private double pdx;
	private double pdz;
	/** 내적이 문턱 아래인 상태 — 처음엔 «아래» 로 둬 첫 움직임에서 안 난다. */
	private boolean turnLow = true;
	/** 최근 가로 움직임(고리) · 든 개수 · 다음 자리 · 꺾기 뒤 쉬는 틱. */
	private final double[] hx = new double[TURN_WINDOW];
	private final double[] hz = new double[TURN_WINDOW];
	private int hn;
	private int hi;
	private int turnCool;

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
		// 꺾기 · 멈춤 — 내적이 문턱 아래로 «들어가는» 틱에만(서 있는 동안은 계속 아래라 다시 안 난다)
		boolean low = dx * this.pdx + dz * this.pdz < TURN_DOT;
		boolean turn = low && !this.turnLow;
		this.turnLow = low;
		this.pdx = dx;
		this.pdz = dz;
		boolean onGround = in.onGround && this.grounded;
		boolean bend = onGround && this.turnCool == 0 && bent(dx, dz, horiz);
		if (onGround && horiz > TURN_SPEED) {
			this.hx[this.hi] = dx;
			this.hz[this.hi] = dz;
			this.hi = (this.hi + 1) % TURN_WINDOW;
			this.hn = Math.min(TURN_WINDOW, this.hn + 1);
		} else if (!onGround) {
			this.hn = 0;
		}
		if (this.turnCool > 0) {
			this.turnCool--;
		}
		if ((turn || bend) && onGround) {
			out.add(new Event("wander" + sneak, 1, in.sprinting, in.y));
			this.turnCool = TURN_COOLDOWN;
			this.hn = 0;
		}

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
			String action = up ? "stairs_up" : base(in, onLadder);
			double interval = (onLadder ? LADDER : up ? STAIR : STRIDE * in.stride) * in.size;
			if (in.quadruped && !onLadder && !up) {
				interval = quadInterval(interval, action);
			}
			if (this.acc >= interval) {
				out.add(new Event(action + sneak, 1, in.sprinting, in.y));
				if (in.quadruped && action.equals("step")) {
					stepped();
					// 네 발 — 걷기면 같은 틱에 한 발 더(PF)
					out.add(new Event(action + sneak, 1, in.sprinting, in.y));
				} else if (in.quadruped) {
					stepped();
				}
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
				// 멈춤 소리는 위 꺾기 판정이 멈추는 순간 이미 냈다(PF) — 여기선 쌓인 거리만 버린다
				this.acc = 0.0;
				this.stepY = in.y;
				this.standTicks = standDelay();
			} else if (this.still > STILL_TICKS && --this.standTicks <= 0) {
				// 정지(D26) — 멈춘 채 0.5 ~ 3초마다 · 리팩에 그 이름이 있을 때만 난다(엔진이 가른다)
				out.add(new Event("stand" + sneak, 1, in.sprinting, in.y));
				this.standTicks = standDelay();
			}
		} else {
			this.still = 0;
		}
		return out;
	}

	/** 최근 5틱 안의 움직임과 65° 넘게 어긋났나 — 지금도 빠르기 문턱을 넘을 때만. */
	private boolean bent(double dx, double dz, double speed) {
		if (speed <= TURN_SPEED) {
			return false;
		}
		for (int k = 1; k <= this.hn; k++) {
			int j = ((this.hi - k) % TURN_WINDOW + TURN_WINDOW) % TURN_WINDOW;
			double ox = this.hx[j];
			double oz = this.hz[j];
			double os = Math.sqrt(ox * ox + oz * oz);
			if ((dx * ox + dz * oz) / (speed * os) < TURN_COS) {
				return true;
			}
		}
		return false;
	}

	private int standDelay() {
		return 10 + (int) (this.rnd.getAsDouble() * 50);
	}

	/** PF 네 발 박자(QuadrupedModifier) — 걷기: 세 걸음 주기 · 가운데 걸음만 짧게 · 달리기: 첫 걸음 × 0.8 · 나머지 × 0.3. */
	private double quadInterval(double d, String action) {
		if (action.equals("run")) {
			return d * (this.hoof == 0 ? 0.8 : 0.3);
		}
		if (!action.equals("step")) {
			return d;
		}
		double pond = this.hoofSeed * this.hoofSeed * 0.2;
		return this.hoof == 1 ? d * pond * 0.925 : d * (1.0 - pond) * 0.925;
	}

	private void stepped() {
		if (this.hoof == 0 || this.hoof == 2) {
			this.hoofSeed = this.rnd.getAsDouble();
		}
		this.hoof = (this.hoof + 1) % 3;
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

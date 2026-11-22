<!-- @component The empty inbox's picture: a sun setting over a calm horizon, in the theme's colors. Android draws the same one. -->
<script lang="ts">
  // The sun's center and radius, and where its rays start and end.
  const SUN_X = 80;
  const SUN_Y = 88;
  const SUN_RADIUS = 30;
  const RAY_FROM = 36;
  const RAY_LENGTH = 10;
  // Seven rays fanned evenly from 10° to 170° above the horizon.
  const RAY_ANGLES = [10, 36.67, 63.33, 90, 116.67, 143.33, 170];
  const rays = RAY_ANGLES.map((degrees) => {
    const radians = (degrees * Math.PI) / 180;
    const cos = Math.cos(radians);
    const sin = Math.sin(radians);
    return {
      degrees,
      x1: SUN_X + RAY_FROM * cos,
      y1: SUN_Y - RAY_FROM * sin,
      x2: SUN_X + (RAY_FROM + RAY_LENGTH) * cos,
      y2: SUN_Y - (RAY_FROM + RAY_LENGTH) * sin,
    };
  });
</script>

<svg
  viewBox="0 0 160 120"
  width="160"
  height="120"
  aria-hidden="true"
  fill="none"
  stroke-width="3"
  stroke-linecap="round"
>
  <path
    d={`M${SUN_X - SUN_RADIUS} ${SUN_Y} A${SUN_RADIUS} ${SUN_RADIUS} 0 0 1 ${SUN_X + SUN_RADIUS} ${SUN_Y} Z`}
    class="fill-accent"
    fill-opacity="0.2"
    stroke="none"
  />
  {#each rays as ray (ray.degrees)}
    <line
      x1={ray.x1}
      y1={ray.y1}
      x2={ray.x2}
      y2={ray.y2}
      class="stroke-accent"
    />
  {/each}
  <line x1="16" y1="88" x2="144" y2="88" class="stroke-muted" />
  <line x1="52" y1="100" x2="76" y2="100" class="stroke-muted" />
  <line x1="86" y1="108" x2="112" y2="108" class="stroke-muted" />
</svg>

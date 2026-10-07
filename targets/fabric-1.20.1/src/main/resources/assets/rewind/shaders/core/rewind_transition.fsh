#version 150

// 过渡后处理，三种效果共用一支着色器，用 EffectMode 选：
//   EffectMode == 1  存档：提高饱和度
//   EffectMode == 2  死亡回溯：高斯模糊 + 视野红边（红边先盖住外圈一大片 → 退成边上一圈椭圆 → 快速淡出）
//   其它             读档：高斯模糊（世界中间会消失，靠它 + 最后一帧副本遮住那段）
// EffectStrength 从 0 淡入到 1 再淡出回 0，为 0 时输出与原画面完全一致。

uniform sampler2D Sampler0;
uniform float EffectStrength;
uniform int EffectMode;
uniform float SaturationBoost;
uniform float BlurRadius;
uniform float DeathReach;
uniform vec2 TexelSize;

in vec2 texCoord;

out vec4 fragColor;


void main() {
    float strength = clamp(EffectStrength, 0.0, 1.0);
    vec4 original = texture(Sampler0, texCoord);

    if (EffectMode == 1) {
        float luma = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
        // strength 为 0 时插值系数正好是 1.0，逐位还原原画面
        vec3 boosted = mix(vec3(luma), original.rgb, 1.0 + strength * SaturationBoost);
        fragColor = vec4(clamp(boosted, 0.0, 1.0), 1.0);
        return;
    }

    // 单遍 13 抽样高斯，半径随强度增长（读档、死亡回溯都用它把中间那段世界变化盖住）
    vec2 offsetStep = TexelSize * (strength * BlurRadius);
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int i = -6; i <= 6; i++) {
        float offset = float(i);
        float weight = exp(-(offset * offset) / 18.0);
        sum += texture(Sampler0, texCoord + offsetStep * offset).rgb * weight;
        weightSum += weight;
    }

    vec3 color = mix(original.rgb, sum / weightSum, strength);

    // 死亡回溯：视野大红边（血色的半透明暗角）。DeathReach 是「红从屏幕四边往里铺到多深」，
    // 单位是半屏高：开始那一瞬盖住外圈一大片，随后退到只留边上的一圈椭圆，回溯结束后再快速缩到 0。
    // 颜色与最大不透明度是这里的常数、不是配置项。
    if (EffectMode == 2) {
        float aspect = TexelSize.y / max(TexelSize.x, 1.0e-6);      // 屏宽 / 屏高
        vec2 rel = (texCoord - 0.5) * 2.0;                          // 每轴 -1..1
        // 物理距离（以半屏高为单位），横向再放一点 → 内边界是横向椭圆而不是矩形
        float dist = length(vec2(rel.x * aspect / 1.35, rel.y));
        float reach = clamp(DeathReach, 0.0, 1.5);
        float inner = 1.0 - reach;                                  // 红的内边界
        // 过渡带放得宽一点、走 smoothstep，别看起来是一条线性的色带
        float band = smoothstep(inner - 0.30, inner + 0.30, dist);
        float amount = band * smoothstep(0.0, 0.25, reach) * 0.45;  // 半透明，不挡视野
        color = mix(color, vec3(0.52, 0.02, 0.03), amount);
    }

    fragColor = vec4(color, 1.0);
}

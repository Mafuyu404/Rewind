#version 150

// 过渡后处理，两种效果共用一支着色器，用 EffectMode 选：
//   EffectMode == 1  存档：提高饱和度
//   其它             读档：高斯模糊（世界中间会消失，靠它 + 最后一帧副本遮住那段）
// EffectStrength 从 0 淡入到 1 再淡出回 0，为 0 时输出与原画面完全一致。

uniform sampler2D Sampler0;
uniform float EffectStrength;
uniform int EffectMode;
uniform vec2 TexelSize;

in vec2 texCoord;

out vec4 fragColor;

// 满强度时把饱和度放到 1.9 倍：够看得出来，又不至于糊成一片色块
const float SATURATION_BOOST = 0.9;

void main() {
    float strength = clamp(EffectStrength, 0.0, 1.0);
    vec4 original = texture(Sampler0, texCoord);

    if (EffectMode == 1) {
        float luma = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
        // strength 为 0 时插值系数正好是 1.0，逐位还原原画面
        vec3 boosted = mix(vec3(luma), original.rgb, 1.0 + strength * SATURATION_BOOST);
        fragColor = vec4(clamp(boosted, 0.0, 1.0), 1.0);
        return;
    }

    // 单遍 13 抽样高斯，半径随强度增长
    vec2 offsetStep = TexelSize * (strength * 9.0);
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int i = -6; i <= 6; i++) {
        float offset = float(i);
        float weight = exp(-(offset * offset) / 18.0);
        sum += texture(Sampler0, texCoord + offsetStep * offset).rgb * weight;
        weightSum += weight;
    }

    fragColor = vec4(mix(original.rgb, sum / weightSum, strength), 1.0);
}

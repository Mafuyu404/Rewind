#version 150

// 读档过渡的后处理：高斯模糊。EffectStrength 从 0 淡入到 1 再淡出回 0，
// 为 0 时输出与原画面完全一致。
// 存档的「广角」不走这里——那是直接推摄像机 FOV（见 RewindScreens.onComputeFov）。

uniform sampler2D Sampler0;
uniform float EffectStrength;
uniform vec2 TexelSize;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float strength = clamp(EffectStrength, 0.0, 1.0);
    vec4 original = texture(Sampler0, texCoord);

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

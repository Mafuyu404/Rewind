#version 150

// 全屏四边形：顶点直接给 0..1 的屏幕坐标，和原版 blit_screen 保持同一套约定
in vec3 Position;

out vec2 texCoord;

void main() {
    vec2 screenPos = Position.xy * 2.0 - 1.0;
    gl_Position = vec4(screenPos.x, screenPos.y, 1.0, 1.0);
    texCoord = Position.xy;
}

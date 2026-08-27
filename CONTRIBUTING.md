# Contributing

We welcome contributions via pull requests (PRs). You can contribute in the following areas:

- Fixing bugs
- Adding features
- Adding shader support

## Rules

- Using AI is allowed, but you must provide a full description of the changes.

## Adding Shader Support

The core interface for shader pack compatibility is located at:

`src/main/kotlin/net/omori_sunny/create_waterparked/client/compat/shaderpack/ShaderpackWaterAdapter.kt`

To add support for a new shader pack:

1. Create a new adapter in the `src/main/kotlin/net/omori_sunny/create_waterparked/client/compat/shaderpack/` directory.
2. Implement the required interface methods based on the shader pack's specific behavior.

Built-in adapters are already provided for BSL and Complementary shaders.

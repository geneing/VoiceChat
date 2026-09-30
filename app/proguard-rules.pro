# Keep rules are intentionally empty for the scaffold: minification is disabled
# and no library requires rules yet. Add per-library rules in the milestone that
# introduces the library (see docs/decisions.md §3 for the model/runtime list).

# ONNX Runtime for Android (M10), scoped to the pinned Smart Turn v3.2 artifact.
# The decision record requires keeping the JNI-backed `ai.onnxruntime` classes
# (docs/decisions.md §3.1, §3.3).
-keep class ai.onnxruntime.** { *; }

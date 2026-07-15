# R8 rules for release builds. Debug builds don't minify.
#
# Empty beyond the defaults for now — the Phase 0 scaffold has nothing that
# reflection can break. Rules get added by the phases that introduce the risk:
#
#  - Phase 5: the gateway's JSON models. Moshi/Retrofit resolve them
#    reflectively, so R8 will happily rename fields it thinks are unused and
#    the request silently serialises to the wrong shape. Keep the model
#    classes and generated adapters.
#  - Phase 3/8: Room entities.
#
# Worth stating plainly: minification is only exercised in release builds, and
# release builds only happen at Phase 11. That means an R8 bug in the gateway
# client would first appear in the APK the agent actually installs, after every
# debug build looked fine. Whoever wires up Phase 11 should run a release build
# through CI early rather than discovering this at tag time.

# Keep source line numbers in stack traces; the file name is hidden anyway.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

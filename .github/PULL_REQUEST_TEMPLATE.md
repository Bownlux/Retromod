<!--
Open this pull request against the `dev` branch, not `main` or `snapshot`.
See https://bownlux.github.io/Retromod/contributing#branches.

Thanks for sending a PR. The checklist below isn't there to be annoying. It's
the stuff I'd otherwise have to ask you about in review. Filling it in up front
saves a round trip.
-->

## What does this PR do?

<!-- One or two sentences. What changed and why. -->

## Type of change

- [ ] New shim (added support for an MC version transition)
- [ ] New polyfill (reimplemented a removed API)
- [ ] Mapping update (intermediary → Mojang, or SRG-related)
- [ ] Bug fix
- [ ] Refactor / cleanup
- [ ] Docs only
- [ ] Build / CI
- [ ] Other:

## Testing

<!--
How did you test this? Be specific:
- Which mod(s) did you run it against?
- Which host MC version + loader?
- Did you run `mvn test -Dexec.skip=true`?
- If it's a shim or polyfill, did you confirm an actual mod that needed it now loads?
-->

## Checklist

- [ ] If I added a shim, I registered it in `META-INF/services/com.retromod.core.VersionShim`
- [ ] If I added a polyfill provider, I registered it in `META-INF/services/com.retromod.polyfill.PolyfillProvider`
- [ ] I didn't hardcode an MC version string (used `Retromod.TARGET_MC_VERSION` where needed)
- [ ] I didn't delete or weaken existing shims for older MC versions (1.12.2+ shims must stay)
- [ ] `mvn test -Dexec.skip=true` passes locally
- [ ] If this changes user-facing behavior, I updated the relevant page under `docs/`

## Related issues

<!-- Closes #123, refs #456, etc. Leave blank if none. -->

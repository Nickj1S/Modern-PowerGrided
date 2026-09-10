# libs/

Compile-only dependency jars. Not checked in (see `.gitignore`). Populate before building:

Copy these from the "Create Skyforge 1.0.4" PrismLauncher instance's `mods/` folder
(the last three are jarjar'd inside `create-1.21.1-6.0.10.jar` under `META-INF/jarjar/`):

- create-1.21.1-6.0.10.jar
- architectury-13.0.11-neoforge.jar
- powergrid-mc1.21.1-0.6.1.jar
- Registrate-MC1.21-1.3.0+67.jar        (from create.jar META-INF/jarjar/)
- ponder-neoforge-1.0.82+mc1.21.1.jar   (from create.jar META-INF/jarjar/; also bundles net.createmod.catnip)
- flywheel-neoforge-1.21.1-1.0.6.jar    (from create.jar META-INF/jarjar/)

Runtime testing is done by dropping build/libs/mir-*.jar into that instance's mods folder,
so these jars are only needed at compile time.

Golden traces recorded in game with `#record start <name>` / `#record stop`.
The mod writes them to `.minecraft/config/lazuli/traces/<name>.trace`; copy them here and
`TraceReplayTest` checks that `PlayerSim` reproduces every tick.

| Tick-wipe fix: in-place rewrite of the audio ISR's post-loop bookkeeping wipe mask,
| FUN_40077120 at 0x40077a72..0x40077a8a (24 B, MAIN OS load addresses). See notes/features/tick_wipe_fix.md.
| After the build the 24 bytes at 0x40077a72..0x40077a8a are
|   200680aeffb424034682c480428041f94395ddf443e8012c
| (build/patch.json lists 23 of them, as two runs of 16 B at 0x40077a72 and 7 B at 0x40077a83, because the
| byte at 0x40077a82, 0x43, is the same as in stock), and m68k-elf-objdump (-m m68k:cfv4e) decodes them to
| exactly the instructions below, with the next instruction starting at 0x40077a8a as in stock.
|
| STOCK:   d2 = (local_50 & ~uVar2) | uVar16      <- countdown releases masked, EVENT releases not
| FIXED:   d2 = (local_50 | uVar16) & ~uVar2      <- a voice re-triggered this tick is never wiped
|
| Registers at entry (machine-code read): d3 = uVar2 (triggers), d6 = uVar16 (event releases),
| %fp@(-76) = local_50 (countdown expiries). d0 is dead until the loop's own clrl, so it is free as a temp.
| 0x40077a72 is a branch target (the flush block's skip, 0x400779f4 beqs) and stays an instruction start.
| The 2 bytes needed are found by reaching held[] as priority[] + 300 (0x4395df20 - 0x4395ddf4 = 0x12c),
| the same trick the owner-latch note-off scan at 0x400b2214 uses.

    .text
tick_wipe_fix:
    movel   %d6,%d0             | d0 = uVar16            (event releases)
    orl     %fp@(-76),%d0       | d0 |= local_50         (countdown expiries)
    movel   %d3,%d2             | d2 = uVar2             (voices triggered this tick)
    notl    %d2
    andl    %d0,%d2             | d2 = (uVar16 | local_50) & ~uVar2
    clrl    %d0                 | v = 0                  (as stock)
    lea     0x4395ddf4,%a0      | priority[]             (as stock)
    lea     %a0@(300),%a1       | held[] = priority + 0x12c   (was: lea 0x4395df20,%a1)

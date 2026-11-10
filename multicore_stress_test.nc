# =====================================================================
# multicore_stress_test.nc  --  a 1 to 1.5 minute multicore workout
# =====================================================================
# Runs NC's parallel kernels hard enough to watch every core in the
# IDE's Analysis windows:
#
#   1. Switch on No. of Computation and Execution Time (the dots in the
#      Analysis box) and, in either live window, Cores & threads.
#   2. Press Run.
#
# What each phase shows:
#   Phase 1  a loop whose work grows with every iteration (a column vector
#            40,000 elements longer each time): the order of growth reads
#            O(n^2) (n = iterations of the phase 1 loop), and the progress
#            bar is weighted by work, not iterations.
#   Phase 2  element-wise kernels (sin, cos, exp, +, *.) and a reduction
#            (sum) on a 3000 x 3000 matrix: compute kernels use all cores,
#            reductions up to 16 threads.
#   Phase 3  2000 x 2000 matrix products: the matrix library uses the cores
#            but reports no kernel, so the monitor says "matrix library
#            working".
#
# Results do not depend on the thread count; only the speed does. Try
# set_threads(1) before running to compare, and set_threads(0) to go back.
# sum() over long vectors and -A are fast since 2026-11-05 (they were 160x
# and 17x slower than the matrix forms before), so phase 1 uses vectors and
# phase 2 uses -A again.
# Measured on a 28-thread PC, Release nc.exe (2026-11-05): 66 s in all
# (21 s + 30 s + 15 s). A Debug nc.exe is several times slower:
# thread_info().build says which one is running.
# =====================================================================

info = thread_info();
publish("nc build:", info.build);
publish("threads:", get_threads(), "of", info.logical_processors, "logical processors");

# ---- Phase 1: growing work, O(n^2) ------------------------------------
tic();
s1 = 0;
for i = [1:230]:
   x = ones(40000 * i, 1) *. (1 / i);
   s1 = s1 + sum(sqrt(x + 1) *. log(x + 2));
fend
t1 = toc();
publish("phase 1 (growing loop) done, checksum", s1);

# ---- Phase 2: element-wise kernels and reductions -----------------------
\\ tic();
\\ A = ones(3000, 3000) *. 0.5;
\\ s2 = 0;
\\ for k = [1:200]:
\\    B = sin(A) *. cos(A) + exp(-A);
\\    s2 = s2 + sum(B);
\\ fend
\\ t2 = toc();
\\ publish("phase 2 (element-wise kernels) done, checksum", s2);
\\
\\ # ---- Phase 3: matrix products ---------------------------------------
\\ tic();
\\ M = ones(2000, 2000) *. 0.001;
\\ s3 = 0;
\\ for k = [1:90]:
\\    C = M * M;
\\    s3 = s3 + sum(C);
\\ fend
\\ t3 = toc();
\\ publish("phase 3 (matrix products) done, checksum", s3);
\\
\\ publish("phase times:", t1, t2, t3);

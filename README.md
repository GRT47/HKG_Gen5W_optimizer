# HKG Gen5W (`S5W_M`) 헤드유닛 성능 측정 및 반응성 최적화 프로젝트

현대·기아 표준형 5세대 와이드 내비게이션(**Gen5W `S5W_M`**, Telechips `tcc897x` / `tcc803x`, Android `4.4.2` API 19)의 실제 병목을 실차 계측 기반으로 분석하고, **정량적 전후 비교 계측 패키지(`hkg.sysbench`)**와 **무상주 런타임 최적화 패키지(`hkg.optimize`)**를 제공하는 저장소입니다.

> **다운로드 (GitHub Releases)**: USB 설치 번들([HKG_5W_Optimize_USB.zip](https://github.com/GRT47/HKG_Gen5W_optimizer/releases/latest), [HKG_5W_SysBench_USB.zip](https://github.com/GRT47/HKG_Gen5W_optimizer/releases/latest))은 [GitHub Releases](https://github.com/GRT47/HKG_Gen5W_optimizer/releases/latest)에서 받을 수 있습니다.

---

## 실차 벤치마크 검증 요약 (`S5W_M`)

| 측정 지표 | 순정 (BEFORE) | 최적화 (AFTER) | 개선 폭 |
| :--- | :---: | :---: | :---: |
| UI 스레드 스케줄링 지연 (`run_delay`) | 100% 기준 | - | **`-39.6%`** |
| Binder IPC P99 응답 지연 | 100% 기준 | - | **`-52.9%`** |
| CPU0 하드웨어 인터럽트 편중률 | 99.97% | 29.50% | CPU1~3 분산 |
| KSM 메모리 스캔 CPU 점유 | 100% 기준 | 0% (`run=0`) | **`-100%`** |
| 활성 부하 구간 스토리지 IOwait | 100% 기준 | - | **`-67.9%`** |
| 최적화 패키지 유휴 상주 메모리 | - | **`0 MB`** | 완전 무상주 |

---

## 패키지 구성

1. **1단계 (`package_sysbench` / `hkg.sysbench v1.2.3`) — 시스템 성능 계측 & 커널 프로브 패키지**
   - **관측자 효과 0% & RAM 디스크**: 측정기 상시 백그라운드 데몬이 없으며(`[✕ 종료]` 시 메모리 점유 `0%`), 휘발성 PID/세션 상태는 `/dev/hkg_tmp/`(`tmpfs`)에 기록합니다. Phase 1(아이들 계측) 동안 자식 프로세스 생성(`fork`)과 디스크 쓰기를 일절 수행하지 않습니다.
   - **부트 프로브 (`svc/boot-probe.sh`, `mode=background`)**: 부팅 완료 직후 순정 `read_ahead_kb`, `vm.*`, `ksm`, `lmk`, `cpufreq`, `thermal`, `/proc/interrupts`, `smp_affinity` 쓰기 가능 여부(`SMP_AFFINITY_WRITABLE=yes|no`)를 1회 덤프한 뒤 즉시 종료합니다.
   - **`hkg.optimize` 연동**: `hkg.optimize`가 설치/활성화된 경우 `AFTER` 측정 시 `hkg.optimize`를 자동 호출하고 리포트 `activeTweaks`에 `hkg.optimize:...`를 기록합니다.
2. **2단계 (`package_optimize` / `hkg.optimize v1.0.2`) — 독립형 무상주 시스템 최적화 패키지**
   - **RAM 0 MB 원칙 & 홈 화면 바로가기**: 부팅 시 `svc/boot-apply.sh` (`mode=background`)가 부팅 완료 +8초에 커널/VM/eMMC/IRQ/로거/KSM/애니메이션 최적화를 1회 적용하고, +35초 뒤 늦게 뜨는 내비(`com.mnsoft.navi`)·외부 데몬 스레드 우선순위를 재정렬(`refresh-sched`)한 뒤 프로세스를 완전히 종료합니다. UI(`launch.sh show`)도 `[✕ 닫기]` 또는 `[💾 설정 저장 후 닫기]` 시 프로세스를 종료하여 상주 메모리를 점유하지 않습니다. 또한 **HMG-API**가 설치된 차량에서는 설치 시 자동으로 `hkg-optimize` 모드를 서명·등록해 순정 홈 화면 앱 목록 및 아이콘 편집기에 **`HKG 최적화`** 바로가기를 추가하며(다음 부팅부터 표시), 미설치 차량에서는 순수 NEMESIS 패키지로 동일하게 동작합니다.
   - **6대 실차 검증 최적화 항목**:
     1. 백그라운드 디스크 로거 중지 (`eventlogdump`, `tcc_logd`, `klogger`, `log_summary`, `logcatdump`, `mtlc_logging` — 자식 `logcat` 파이프 보호)
     2. KSM 메모리 스캔 중지 (`ksm/run=0`, `sleep_millisecs=1000`)
     3. VM 캐시압력 & eMMC 큐 최적화 (`vfs_cache_pressure 10000->60`, `swappiness 10`, `dirty_background_ratio 5`, `dirty_ratio 15`, `min_free_kbytes >= 16384`, `read_ahead_kb=512`, `nr_requests`, `rq_affinity=2->1`)
     4. 네비·UI 멀티스레드 & 데몬 우선순위 (`surfaceflinger`/`system_server` UI `-8`, `com.mnsoft.navi` 비-GC 전체 자식 스레드 `-6`, 런처/분할/프로젝션 `-4`, 외부 `app_process` 데몬 예의 바른 양보 `+4`, `tcc_dxb_service +6`, `vrm`/날씨/스포츠 `+10` + `bg_non_interactive` + `ionice idle 7`)
     5. 하드웨어 인터럽트 멀티코어 분산 (`CPU1~3` 라운드로빈, 타이머/WDT/UART/IPI 보호)
     6. 화면 전환 애니메이션 가속 프리셋 (`1.0x`, `0.5x`, `0.25x [0.25/0.25/0.50]`, `0.0x`) + `[시동 시 자동 적용: ON/OFF]` (`anim_guard.conf` 부팅 안전장치 포함)

---

## 빌드 방법

필수 도구: JDK 8+ (`javac`, `java`), Android SDK `build-tools` (`d8`), Python 3.10+

```bash
# 최적화 패키지 빌드 (호스트 6/6 테스트 + DEX 035 -> HKG_5W_Optimize_USB.zip)
python package_optimize/build_package.py

# 벤치마크 패키지 빌드 (호스트 7/7 테스트 + DEX 035 -> HKG_5W_SysBench_USB.zip)
python package_sysbench/build_package.py
```

---

## 실차 USB 설치 및 사용 방법

### 0. 사전 요구 사항 (설치 순서 중요)

> **홈 화면 `HKG 최적화` 바로가기 타일을 사용하려면 `HKG_5W_Optimize_USB.zip` 설치 전에 NEMESIS와 [HMG-API](https://github.com/VysseCodes/HMG-API)가 먼저 설치되어 있어야 합니다.**  
> 설치 스크립트(`scripts/post.sh`)가 설치 시점에 `/system/framework/hmgapi-hook.jar` 및 `/data/hmgapi` 존재 여부를 확인해 `hkg-optimize` HMG-API 모드와 홈 화면 트램펄린 앱을 자동 서명·등록합니다.

1. 헤드유닛에 **NEMESIS** 및 **[HMG-API](https://github.com/VysseCodes/HMG-API)**를 먼저 설치하고 재부팅까지 완료합니다.
2. FAT32 USB 메모리 최상단(루트)에 `HKG_5W_Optimize_USB.zip` 압축을 해제(`NEMESIS.data`, `packages/hkg.optimize.zip`)하여 차량 USB 포트에 연결합니다.
3. 설치 완료 직후 `HKGSysOptOverlay` 설정 창이 자동으로 열리며(`앱/정보`에서는 즉시 실행 가능), **메인 홈 화면(`전체 메뉴`) 및 `홈 화면 아이콘 편집`의 `HKG 최적화` 아이콘은 다음 헤드유닛 재부팅 후**부터 나타납니다.

* **HMG-API 설치 순서가 바뀐 경우**: HMG-API 설치 완료 후 `HKG_5W_Optimize_USB.zip`을 USB로 1회 다시 설치하면 홈 화면 바로가기가 등록됩니다.
* **HMG-API 미설치 차량**: 홈 화면 바로가기만 등록되지 않으며, 재부팅 시 자동 최적화 적용 및 NEMESIS/CLI 실행은 정상 동작합니다.

### 1. 패키지별 사용 방법

1. **최적화 전용 (`HKG_5W_Optimize_USB.zip`)**:
   - 홈 화면 `HKG 최적화` 타일 또는 설치 직후 열리는 오버레이(`HKGSysOptOverlay`)에서 **[🚀 원클릭 전체 최적화 적용]**, 개별 항목 ON/OFF, 애니메이션 프리셋, **[시동 시 자동 적용: ON/OFF]**를 제어할 수 있습니다.
   - CLI 제어: `sh /data/nemesis/apps/hkg.optimize/bin/launch.sh <cli-on|cli-off|cli-status|show|stop>`
2. **성능 측정 (`HKG_5W_SysBench_USB.zip`)**: `hkg.sysbench` 설치 후 부팅 90초 이상 지난 정차(`P`) 상태에서 `[① BEFORE (순정)]` → `[② AFTER (최적화)]`를 측정하면 USB(`/storage/usb0/HKG_SysBench/`)에 `summary.txt`, `Gen5W_Bench_Report.html`, JSON 리포트가 저장됩니다.

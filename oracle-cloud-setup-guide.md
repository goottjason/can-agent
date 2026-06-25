# Oracle Cloud Always Free 인스턴스 생성 완전 가이드

## 현재 상황

| 항목 | 내용 |
|------|------|
| 계정 상태 | PAYG 전환 완료 |
| 목표 | 여러 프로젝트 Docker로 호스팅 (can-agent, sbshop-agent 외) |
| 트래픽 | 개인용, 적음 |
| 홈 리전 | 서울 1순위 → 춘천 2순위 |

---

## Always Free로 받을 수 있는 리소스

| 항목 | 상시 무료 |
|------|-----------|
| ARM Compute (Ampere A1) | **4 OCPU + 24GB RAM** (1~4개 VM으로 분할 가능) |
| AMD Micro VM | 2개 (각 1/8 OCPU, 1GB RAM) |
| 블록 스토리지 | **200GB** (부트 볼륨 + 데이터 볼륨 합산) |
| 오브젝트 스토리지 | 10GB |
| 아웃바운드 트래픽 | 월 10TB |

ARM 인스턴스가 핵심. 4코어 24GB면 Docker 컨테이너 여러 개를 여유롭게 돌릴 수 있음.

---

## PAYG 전환의 이점

- 인스턴스 생성 우선순위가 높아짐 → "Out of host capacity" 오류 감소
- Always Free 범위 내에서 사용하면 절대 과금 안 됨
- Oracle이 인스턴스를 회수할 위험이 줄어듦

**안전장치**: Billing and Cost Management > Budget에서 월 $1 한도를 설정하면, Always Free를 초과하는 사용이 발생할 때 알림을 받을 수 있음.

---

# 인스턴스 생성 옵션 상세 설명

OCI 콘솔에서 "Create Instance"를 누르면 나타나는 **Basic Information** 화면의 모든 옵션을 설명합니다.

---

## 1. Name (인스턴스 이름)

이 인스턴스를 식별하는 이름입니다. OCI 콘솔 목록에서 보이는 이름이므로, 나중에 여러 개의 인스턴스를 관리할 때 구분할 수 있게 지어주세요.

**추천 설정**: `arm-server` 또는 용도에 맞는 이름 (예: `can-agent-server`)

---

## 2. Create in compartment (컴파트먼트 선택)

**컴파트먼트(Compartment)**란 OCI 리소스를 구분하는 논리적 컨테이너입니다. 파일 시스템의 폴더와 비슷합니다.

| 개념 | 설명 |
|------|------|
| Root compartment | 가입 시 자동 생성되는 최상위 compartment |
| 사용자 정의 compartment | 필요에 따라 하위 compartment를 만들 수 있음 |

**현재 설정**: `goottjason (root)` — 유지하면 됩니다.

> 처음에는 root compartment를 쓰는 게 편합니다. 나중에 리소스를 구분하고 싶을 때 compartment를 만들면 됩니다.

---

## 3. Placement - Availability Domain (가용성 도메인)

**가용성 도메인(AD)**은 물리적으로 분리된 데이터 센터입니다. 하나의 AD 장애가 나도 다른 AD는 살아있어서 고가용성을 제공합니다.

### 대한민국 리전 AD 구성

| 리전 | AD 개수 | 설명 |
|------|---------|------|
| 서울 (Seoul) | 2개 | AD-1, AD-2 |
| 춘천 (Chuncheon) | 1개 | AD-1만 존재 |

**현재 설정**: `xUOt:AP-CHUNCHEON-1-AD-1` — 춘천 리전, AD 1개

> Always Free 인스턴스는 **하나의 AD**에만 만들 수 있습니다. 여러 AD에 걸쳐 분산하려면 유료 계정이 필요합니다.

**추천**: 서울이면 AD-1 또는 AD-2 아무거나. 춘천이면 AD-1만 있음.

---

## 4. Advanced options - Capacity type (용량 유형)

인스턴스가 어떤 물리적 호스트에 배치되는지를 결정합니다.

### 용량 유형 비교

| 유형 | 설명 | 추천 대상 |
|------|------|-----------|
| **On-demand capacity** | 공유 호스트에 배치. 필요할 때 즉시 생성 가능. **일반적인 사용에 추천** | 개인 프로젝트, 개발/테스트 |
| Preemptible capacity | 공유 호스트에 배치. 오라클이 필요하면 언제든 회수 가능 (최대 24시간). 비용 50~70% 할인 | 비용 절감이 중요한 일회성 작업 |
| Capacity reservation | 특정 호스트에 자리를 미리 확보. 유료 기능 | 프로덕션, SLA가 필요한 서비스 |
| Dedicated host | 전용 호스트 할당. 최고 수준의 격리 | 규제 준수, 완전한 격리 필요 |

**현재 설정**: `On-demand capacity` — 유지

> Always Free 사용자에게는 **On-demand capacity**가 유일한 선택지입니다. 나머지 3개는 유료 기능입니다.

---

## 5. Cluster placement group (클러스터 배치 그룹)

동일한 물리적 호스트 또는 인접 호스트에 여러 인스턴스를 배치하는 기능입니다.

| 설정 | 설명 |
|------|------|
| **OFF (기본값)** | 인스턴스가 OCI에 의해 임의로 배치됨 |
| ON | 여러 인스턴스를 물리적으로 가깝게 배치. 네트워크 지연 시간 최소화 |

**현재 설정**: OFF (토글 꺼짐) — 유지

> 클러스터 배치 그룹은 여러 인스턴스 간의 저지연 네트워킹이 필요할 때 사용합니다. 개인 프로젝트에는 불필요합니다.

---

## 6. Fault domain (결함 도메인)

하나의 AD(가용성 도메인) 내에서 다시 물리적으로 분리된 구역입니다. AD가 2개 이상인 리전(서울 등)에서만 의미가 있습니다.

| 설정 | 설명 |
|------|------|
| **(비워둠 — 자동 배치)** | OCI가 알아서 최적의 결함 도메인에 배치 |
| FD-1, FD-2, FD-3 | 특정 결함 도메인을 수동으로 선택 |

**현재 설정**: 비워둠 (자동) — 유지

> 춘천은 AD가 1개뿐이라 Fault domain 선택이 큰 의미가 없습니다. 서울 리전이라면 Fault domain을 지정하지 않는 게 OCI가 알아서 분산시켜주므로 더 안전합니다.

---

## 7. Image (이미지 — 운영체제)

인스턴스에 설치될 **운영체제(OS)**입니다. OCI가 제공하는 플랫폼 이미지와 사용자 정의 이미지가 있습니다.

### 추천 이미지 비교

| 이미지 | 특징 | 패키지 매니저 |
|--------|------|--------------|
| **Ubuntu 24.04 Minimal** | Docker 자료 압도적 많음, 초보 친화적 | `apt` |
| Ubuntu 22.04 Minimal | 안정적, LTS 지원 | `apt` |
| Oracle Linux 9 | OCI 최적화 잘 되어 있음 | `dnf` |
| Oracle Linux 8 | 안정적, LTS 지원 | `dnf` |

**현재 설정**: Ubuntu 24.04 Minimal (ARM64)

> Docker 이미지는 반드시 **ARM64** 빌드여야 합니다. "Change image" 버튼을 눌러 Ubuntu를 선택합니다. Minimal 버전이면 불필요한 패키지가 적어서 가볍습니다.

---

## 8. Shape (프로세서/하드웨어 구성)

인스턴스의 **CPU, 메모리, 네트워크 대역폭**을 결정합니다. **가장 중요한 설정**입니다.

### OCI 주요 Shape 비교

| Shape | 프로세서 | OCPU | RAM | 비용 | Always Free |
|-------|----------|------|-----|------|-------------|
| **VM.Standard.A1.Flex** | **ARM Ampere A1** | **1~4** | **1~24GB** | **무료** | **O (핵심!)** |
| VM.Standard.E2.1.Micro | AMD | 1/8 | 1GB | 무료 | O |
| VM.Standard.E5.Flex | AMD | 1~N | 1~N | 유료 | X |
| VM.Standard.E4.Flex | AMD | 1~N | 1~N | 유료 | X |

### Shape 설정 방법

1. **"Change shape"** 버튼 클릭
2. **Ampere** (ARM) 탭 선택
3. **VM.Standard.A1.Flex** 선택
4. OCPU: **4** / Memory: **24GB** 설정

> ARM(Ampere)은 Always Free로 최대 4 OCPU + 24GB를 무료로 줍니다. AMD Micro는 1GB RAM이라 Docker 여러 개 돌리기에 부족합니다.
>
> **주의**: 현재 캡쳐에서 Shape가 `VM.Standard.E5.Flex (AMD, 1 OCPU, 12GB)`로 되어 있습니다. 이것은 유료 Shape입니다. 반드시 ARM A1으로 변경해야 합니다.

---

## 9. Advanced options - Management - Instance metadata service

인스턴스의 메타데이터에 접근하는 방식을 설정합니다.

| 설정 | 설명 |
|------|------|
| **ON (기본값)** | IMDSv2 사용 필수. 인스턴스 메타데이터 접근 시 인증 헤더가 필요 |
| OFF | IMDSv1 허용 (보안에 취약) |

**현재 설정**: ON 유지 — 유지

> IMDSv2는 보안이 강화된 버전입니다. OCI에서 제공하는 이미지는 모두 IMDSv2를 지원하므로 기본값 유지가 안전합니다.

---

## 10. Initialization script (초기화 스크립트)

인스턴스가 부팅될 때 자동으로 실행되는 스크립트입니다. 서버가 처음 켜질 때 Docker 설치 같은 초기 설정을 자동으로 할 수 있습니다.

| 옵션 | 설명 |
|------|------|
| **None (기본값)** | 스크립트 없이 깨끗한 상태로 시작 |
| Choose cloud-init script file | 파일을 업로드하여 실행 |
| Paste cloud-init script | 스크립트를 직접 붙여넣기 |

**현재 설정**: None — 유지

> SSH 접속 후 수동으로 설치하는 게 익숙해지면 cloud-init 스크립트를 쓰면 편리합니다. 지금은 None으로 두고 수동으로 하나씩 설치하는 걸 추천합니다.

---

## 11. Tagging (태깅)

인스턴스에 레이블을 붙여서 리소스를 분류하는 기능입니다.

| 기능 | 설명 |
|------|------|
| 태그 추가 | 키-값 쌍으로 리소스에 태그 부여 |
| 용도 | 비용 추적, 리소스 분류, 정책 기반 관리 |

**현재 설정**: 태그 없음 — 유지

> 개인 프로젝트에서는 태깅이 필요 없습니다. 나중에 여러 리소스를 관리할 때 추가하면 됩니다.

---

## 12. Availability configuration (가용성 설정)

인프라 유지보수 시 인스턴스를 어떻게 처리할지를 설정합니다.

| 옵션 | 설명 | 추천 |
|------|------|------|
| **Let Oracle Cloud Infrastructure choose the best migration option** | OCI가 알아서 가장 좋은 마이그레이션 방법 선택 | **개인 사용자 추천** |
| Use live migration if possible | 유지보수 시 중단 없이 다른 호스트로 이동 | 프로덕션 환경 |
| Send maintenance notification | 유지보수 전 알림 발송, 사전 재부팅 필요 시 알림 | 세밀한 제어가 필요할 때 |

### 하위 옵션

| 옵션 | 설명 | 현재 설정 |
|------|------|-----------|
| Restore instance lifecycle state after infrastructure maintenance | 유지보수 후 인스턴스 상태 복원 (켜져 있으면 다시 켬) | ON 유지 |

**현재 설정**: Let OCI choose + Restore ON — 유지

> 개인 사용자에게는 OCI가 알아서 처리하는 게 가장 편합니다. 유지보수 중 인스턴스가 꺼지더라도 OCI가 다시 켜줍니다.

---

## 13. Oracle Cloud Agent (OCI 에이전트)

인스턴스 내부에서 OCI와 통신하는 경량 에이전트입니다. 메트릭 수집, 모니터링, 보안 스캔 등을 수행합니다.

### Oracle Cloud Agent 플러그인 목록

| 플러그인 | 설명 | 추천 |
|----------|------|------|
| **Custom Logs Monitoring** | 커스텀 로그를 OCI Logging 서비스로 전송 | OFF (불필요) |
| **Compute Instance Run Command** | OCI 콘솔에서 원격으로 명령 실행 | ON 유지 (편리) |
| **Compute Instance Monitoring** | CPU, 메모리 등 기본 메트릭 수집 | ON 유지 |
| **Cloud Guard Workload Protection** | 워크로드 보안 스캔 | OFF (유료 기능) |
| **Block Volume Management** | 블록 볼륨 자동 관리 | ON 유지 (선택) |
| **Bastion** | OCI Bastion 서비스를 통한 접근 | OFF (불필요) |
| Vulnerability Scanning | 취약점 스캔 | OFF (유료 기능) |
| OS Management Hub Agent | OS 패키지 관리 | OFF (불필요) |
| Management Agent | OCI Management Hub 통합 | OFF (불필요) |
| Compute RDMA GPU Monitoring | GPU 모니터링 | OFF (ARM에는 없음) |
| Compute HPC RDMA Auto-Configuration | HPC 네트워크 자동 설정 | OFF (불필요) |
| Compute HPC RDMA Authentication | HPC 네트워크 인증 | OFF (불필요) |

**현재 설정**: 기본값 유지

>OCI Cloud Agent는 리소스를 많이 사용하지 않습니다. 기본값으로 두면 됩니다. 불필요한 플러그인을 꺼도 되지만, 지금은 건드리지 않는 게 편합니다.

---

## 14. Security (보안)

### Shielded instance (방패 인스턴스)

인스턴스가 부팅될 때 **무결성 검증**을 수행하여, 악성 소프트웨어나 무단 수정이 없는지 확인하는 기능입니다.

| 항목 | 설명 |
|------|------|
| **Secure Boot** | 부팅 과정에서 서명된 유효한 키로만 부팅 허용 |
| **Trusted Platform Module (TPM)** | 하드웨어 기반 암호화 키 저장소 제공 |
| **Measured Boot** | 부팅 과정의 각 단계를 기록하여 변조 감지 |

| 설정 | 설명 | 추천 |
|------|------|------|
| **ON** | 보안 강화. 부팅 무결성 검증 활성화 | 프로덕션, 보안 중요 환경 |
| **OFF (기본값)** | 검증 없이 빠르게 부팅 | 개인 개발/테스트 환경 |

> Shielded instance는 보안을 강화하지만, 부팅 시간이 약간 늘어나고 일부 리소스를 사용합니다. 개인 프로젝트에서는 **OFF**가 적합합니다.

### Confidential computing (기밀 컴퓨팅)

하드웨어 수준에서 VM 메모리를 암호화하여, OCI 인프라 관리자도 VM 내부 데이터를 볼 수 없게 하는 기능입니다.

| 설정 | 설명 | 추천 |
|------|------|------|
| ON | VM 메모리 하드웨어 암호화 | 민감한 데이터 처리 시 |
| OFF (기본값) | 일반 VM | 개인 프로젝트 |

> Shielded instance와 Confidential computing은 **둘 중 하나만** 활성화할 수 있습니다.

### Security attributes (보안 속성 — ZPR)

**ZPR (Zero Trust Packet Routing)**은 OCI의 네트워크 보안 서비스입니다. 리소스에 보안 속성을 태깅하면, 정책 기반으로 네트워크 접근을 제어할 수 있습니다.

| 항목 | 설명 |
|------|------|
| 기능 | 리소스별 네트워크 접근 정책 설정 |
| 최대 속성 수 | 3개 |
| 대상 | ZPR 서비스를 사용하는 기업 환경 |

**현재 설정**: No items — 유지

> ZPR은 기업/엔터프라이즈 환경에서 사용하는 기능입니다. 개인 프로젝트에는 불필요합니다.

---

## 15. Primary VNIC (네트워크)

**VNIC(Virtual Network Interface Card)**은 인스턴스를 네트워크에 연결하는 가상 네트워크 카드입니다. 인스턴스가 인터넷과 통신하려면 VNIC이 필수입니다.

### VNIC name

인스턴스의 VNIC에 붙이는 이름입니다. 비워둬도 자동으로 생성됩니다.

### Primary network (기본 네트워크 — VCN)

**VCN(Virtual Cloud Network)**은 OCI 내부의 가상 네트워크입니다. 집 안의 공유기와 비슷합니다.

| 옵션 | 설명 | 추천 |
|------|------|------|
| **Select existing virtual cloud network** | 이미 만든 VCN 선택 | 기존 VCN이 있을 때 |
| **Create new virtual cloud network** | 새 VCN 자동 생성 | **첫 인스턴스 생성 시 추천** |
| Specify OCID | OCID로 직접 지정 | 고급 사용자 |

> **첫 인스턴스면 "Create new virtual cloud network"를 선택하세요.** OCI가 알아서 VCN, 서브넷, 인터넷 게이트웨이 등을 자동으로 만들어줍니다.

### Subnet (서브넷)

VCN 내에서 인스턴스가 속하는 네트워크 구간입니다.

| 옵션 | 설명 | 추천 |
|------|------|------|
| **Select existing subnet** | 기존 서브넷 선택 | 기존 서브넷이 있을 때 |
| **Create new public subnet** | 새 공개 서브넷 자동 생성 | **첫 인스턴스면 이것 선택** |
| Create new private subnet | 새 비공개 서브넷 생성 | 인터넷 접근 불필요할 때 |

> 공개 서브넷(Public subnet)은 외부에서 인터넷으로 접근 가능합니다. 인스턴스에 SSH로 접속하려면 **Public subnet**이 필요합니다.

**현재 설정**: 기존 VCN `vcn-sbshop-agent`, 기존 서브넷 `subnet-sbshop-agent` 사용 중

> 이미 VCN과 서브넷이 있다면 기존 것을 사용해도 됩니다. 다만, 기존 VCN의 보안 규칙이 새 인스턴스에도 적용되는지 확인하세요.

---

## 16. Private IPv4 address assignment (사설 IP 할당)

인스턴스의 **내부(사설) IP 주소**를 설정합니다.

| 옵션 | 설명 | 추천 |
|------|------|------|
| **Automatically assign private IPv4 address** | OCI가 알아서 사용 가능한 다음 IP 할당 | **기본값 유지** |
| Manually assign private IPv4 address | 특정 IP를 수동으로 지정 | 특정 IP가 필요할 때 |
| Provide existing private IPv4 OCID | 기존 사설 IP OCID 지정 | 이미 만든 사설 IP가 있을 때 |

### Subnet IPv4 prefixes

서브넷의 IP 대역입니다. `10.0.0.0/24`는 10.0.0.1~10.0.0.255까지 총 256개의 IP를 사용할 수 있습니다.

**현재 설정**: `Automatically assign` + `10.0.0.0/24` — 유지

---

## 17. Public IPv4 address assignment (공인 IP 할당)

인스턴스를 **인터넷에서 직접 접근**할 수 있게 하는 공인 IP 주소입니다.

| 설정 | 설명 | 추천 |
|------|------|------|
| **ON (토글 켬)** | 공인 IP 자동 할당 | **SSH 접속, 웹 서비스 제공에 필수** |
| OFF | 공인 IP 없음 | 내부 전용 인스턴스 |

**현재 설정**: ON 유지

> 공인 IP가 없으면 인터넷에서 인스턴스에 접근할 수 없습니다. SSH 접속과 Docker 서비스 접근을 위해 **반드시 켜야 합니다.**

---

## 18. IPv6 address assignment (IPv6 할당)

IPv6 주소를 할당할지 설정합니다.

| 설정 | 설명 | 추천 |
|------|------|------|
| OFF (토글 꺼짐) | IPv6 미사용 | **일반적인 개인 사용** |
| ON | IPv6 주소 할당 | IPv6 환경 필요 시 |

**현재 설정**: OFF 유지

> IPv6는 아직 필수적이지 않습니다. 현재 VCN/subnet에서 IPv6를 지원하지 않는 경우도 있으니 OFF로 두세요.

---

## 19. Add SSH keys (SSH 키 설정)

**SSH 키**는 인스턴스에 안전하게 접속하기 위한 암호화 키입니다. 비밀번호 대신 키 기반 인증을 사용합니다.

### SSH 키 옵션 비교

| 옵션 | 설명 | 추천 |
|------|------|------|
| **Generate a key pair for me** | OCI가 새 키 쌍을 생성. 프라이빗키 파일 다운로드 필요 | **처음 사용 시 추천** |
| Upload public key file (.pub) | 기존 공개키 파일 업로드 | 이미 SSH 키가 있을 때 |
| Paste public key | 공개키 내용을 직접 붙여넣기 | 공개키가 텍스트 형태일 때 |
| No SSH keys | SSH 키 없이 생성 | 비권장 (접속 불가) |

### SSH 키 생성 방법 (로컬 PC에서)

```bash
# macOS / Linux
ssh-keygen -t ed25519 -C "your-email@example.com"
# → ~/.ssh/id_ed25519 (프라이빗키)
# → ~/.ssh/id_ed25519.pub (공개키)

# Windows (PowerShell)
ssh-keygen -t ed25519 -C "your-email@example.com"
# → %USERPROFILE%\.ssh\id_ed25519
# → %USERPROFILE%\.ssh\id_ed25519.pub
```

> **프라이빗키는 절대 남에게 주지 마세요.** 프라이빗키가 있으면 인스턴스에 접속할 수 있습니다.
> OCI에서 "Generate a key pair for me"를 선택하면 프라이빗키 파일을 다운로드해줍니다. **이 파일을 안전한 곳에 보관하세요.**

---

## 20. Boot volume (부트 볼륨)

**Boot volume**은 인스턴스가 부팅되는 데 사용되는 디스크입니다. PC의 OS 설치 디스크와 같습니다.

| 옵션 | 설명 | 추천 |
|------|------|------|
| **Specify a custom boot volume size** | 커스텀 부트 볼륨 크기 지정 | **ON으로 변경** |
| Use in-transit encryption | 인스턴스와 볼륨 간 데이터 전송 시 암호화 | ON 유지 |
| Encrypt this volume with a key that you manage | 사용자 관리 키로 볼륨 암호화 | OFF 유지 |

### 부트 볼륨 크기

| 크기 | 설명 |
|------|------|
| **46.6 GB (기본값)** | OS + 기본 패키지 정도 |
| **100 GB** | Docker 이미지 여러 개 설치 시 추천 ★ |
| 200 GB | Always Free 한도 전체 사용 |

> Always Free 블록 스토리지 총 한도는 **200GB**입니다. 부트 볼륨 100GB + 데이터 볼륨 100GB로 나누면 여유롭습니다.

### Block volumes (블록 볼륨)

별도의 추가 디스크를 연결하는 기능입니다.

| 기능 | 설명 |
|------|------|
| Attach block volume | 인스턴스에 추가 블록 볼륨 연결 |

> 지금은 별도 블록 볼륨이 필요 없습니다. 부트 볼륨 100GB면 충분합니다. 나중에 Docker 데이터 저장 공간이 부족해지면 추가하면 됩니다.

---

# 인스턴스 생성 체크리스트

생성 전 반드시 확인할 것:

- [ ] **Name**: 원하는 이름 설정
- [ ] **Compartment**: goottjason (root)
- [ ] **Availability Domain**: 서울 AD-1 또는 춘천 AD-1
- [ ] **Capacity type**: On-demand capacity
- [ ] **Image**: Ubuntu 24.04 Minimal (ARM64)
- [ ] **Shape**: **VM.Standard.A1.Flex** (ARM) — 4 OCPU, 24GB RAM ★
- [ ] **Boot volume**: 100GB로 변경 ★
- [ ] **SSH key**: 공개키 업로드
- [ ] 나머지: 기본값 유지

---

# 다음 단계: 인스턴스 생성 후

인스턴스가 생성되면:
1. **퍼블릭 IP 확인** — 인스턴스 상세 페이지에서 확인
2. **SSH 접속** — `ssh ubuntu@<퍼블릭IP> -i <프라이빗키>`
3. **Docker 설치** — `curl -fsSL https://get.docker.com | sh`
4. **방화벽 설정** — OCI 보안 규칙에서 포트 열기

---

## Always Free 한도 확인 완료

Cost Analysis에서 **$0 과금** 확인됨. Always Free 한도 내에서 정상 사용 중.

---

# "Out of host capacity" 오류 발생 시

ARM 인스턴스는 인기가 많아서 생성이 거부될 수 있습니다.

**해결 방법**:
1. PAYG 전환 (이미 완료) — 이것이 가장 확실
2. 서울이 안 되면 춘천 시도
3. 자동 재시도 스크립트 활용 (OCI CLI)
4. 다른 리전(도쿄, 오사카, 싱가포르) 시도

---

## 인스턴스 접속 정보

| 항목 | 값 |
|------|-----|
| Public IP | `168.107.31.154` |
| Username | `ubuntu` |
| 키 파일 경로 | `/Users/jason/IdeaProjects/can-agent/ssh-key-2026-06-25.key` |
| SSH 접속 명령어 | `ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154` |
| Docker | v29.6.0 |
| Docker Compose | v5.2.0 |

---

# 서버 초기 셋업 (SSH 접속 후) — 완료

```bash
# 1. 시스템 업데이트 ✅
sudo apt update && sudo apt upgrade -y

# 2. Docker 설치 ✅
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu

# 3. Docker Compose 확인 ✅
docker compose version

# 4. 방화벽 설정 (OCI 보안 규칙에서 포트 열기 필요)
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

---

# Docker Compose 구성

## 프로젝트별 컨테이너 배치

```
Oracle Cloud ARM Server (4 OCPU, 24GB)
├── nginx          (리버스 프록시, 256MB)
├── postgresql     (공용 DB, 2~4GB)
├── can-agent      (Spring Boot, 2~4GB)
├── sbshop-agent   (Spring Boot, 2~4GB)
├── react-apps     (React 빌드 → nginx 서빙, 각 256MB~512MB)
├── cloudflared    (Cloudflare Tunnel, 128MB)
└── portainer      (Docker 관리 UI, 선택, 256MB)
```

## docker-compose.yml 골격

```yaml
services:
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d
      - ./nginx/ssl:/etc/nginx/ssl
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: canagent
      POSTGRES_USER: canagent
      POSTGRES_PASSWORD: ${PG_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    restart: unless-stopped

  can-agent:
    build: ./can-agent
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: postgres
    depends_on:
      - postgres
    restart: unless-stopped

  sbshop-agent:
    build: ./sbshop-agent
    restart: unless-stopped

  cloudflared:
    image: cloudflare/cloudflared:latest
    command: tunnel --no-autoupdate run --token ${TUNNEL_TOKEN}
    restart: unless-stopped

volumes:
  pgdata:
```

---

# 도메인 연결 (무료 옵션)

## 옵션 A: Cloudflare Tunnel (추천)

- 장점: 포트를 열지 않아도 됨, 자동 HTTPS, DDoS 보호
- 비용: Tunnel 무료
- 필요: 도메인 (연간 ~$10, Cloudflare에서 구매 가능)

```
사용자 → Cloudflare Edge → [Tunnel] → 서버 내부 Docker 컨테이너
         (HTTPS)                          (HTTP, 포트 노출 불필요)
```

## 옵션 B: 무료 서브도메인

| 서비스 | 도메인 예시 | 특징 |
|--------|-------------|------|
| ZoneABC | `*.zoneabc.net` | Cloudflare Enterprise 기반, 무료 |
| nxtdev.xyz | `*.nxtdev.xyz` | 개발자용, DNS 관리 제공 |

단점: 장기 안정성 불확실

## 옵션 C: 공인 IP 직접 사용

- OCI에서 제공하는 퍼블릭 IP로 직접 접근
- 포트 열기 필요 (80, 443)
- SSL은 Let's Encrypt로 수동 설정

**추천**: 처음에는 공인 IP + Nginx로 시작, 도메인 확보 후 Cloudflare Tunnel로 전환

---

# 프로젝트별 Dockerfile 예시

## can-agent (Spring Boot + JPA)

```dockerfile
FROM eclipse-temurin:17-jre-arm64
WORKDIR /app
COPY build/libs/can-agent-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## React 앱

```dockerfile
# 빌드
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 서빙
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

---

# 비용 요약

| 항목 | 비용 |
|------|------|
| Oracle Cloud ARM 인스턴스 | **$0** (Always Free) |
| 블록 스토리지 200GB | **$0** (Always Free) |
| Cloudflare Tunnel | **$0** |
| 도메인 (Cloudflare 구매 시) | 연간 ~$10 (선택) |
| **합계** | **$0 ~ $10/년** |

---

# 주의사항

1. **ARM64 호환성**: Docker 이미지는 반드시 `linux/arm64` 빌드여야 함
2. **아이들 회수**: CPU/네트워크/메모리 사용량이 7일간 20% 미만이면 Oracle이 인스턴스를 회수할 수 있음 → 가벼운 cron 작업으로 방지
3. **도커 데이터 디렉토리**: 부트 볼륨이 빨리 차므로, Docker 데이터를 데이터 볼륨으로 옮기는 것을 추천
4. **방화벽 이중 설정**: OCI 보안 규칙 + OS(UFW) 둘 다 열어야 함

---

# 진행 순서

1. ✅ 오라클 클라우드 콘솔에서 ARM 인스턴스 생성
2. ✅ SSH 접속 후 Docker + Docker Compose 설치
3. ✅ `docker-compose.yml` 작성
4. ✅ OCI 보안 규칙에서 포트 22, 80, 443 열기
5. ✅ 외부 접속 테스트 완료 (HTTP 200)
6. (선택) Cloudflare Tunnel 설정
7. (선택) 프로젝트별 Dockerfile 작성 및 배포

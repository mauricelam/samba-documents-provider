# Samba Documents Provider
## Overview
This is an Android app that extends the built in File Manager to support connecting to SMB
file shares.

This app is built on top of Samba 4.5.1.

## Setup
### Prerequisite
Android SDK and NDK r23b or above are required to build this app. Android Studio is highly
recommended.

This build guide is only tested on Ubuntu. Please see the Mac section below for how to
build on a mac. Changes to make it build on other platforms are welcome.


### Build Steps
1. `git clone --recurse-submodules https://github.com/mauricelam/samba-documents-provider.git`
2. `cd samba-documents-provider/samba`
3. ** If building on non-Linux devices, see the section below **
3. Modify configure.sh to change $NDK to point to your NDK folder.
4. Uncomment corresponding flags in configure.sh to compile for different architecture.
   Uncomment flags for ARMv7 in addition to 32-bit ARM to compile it for ARMv7.
5. Run `configure.sh` to configure Samba project.
6. Run `compile.sh` to compile libsmbclient.so.
7. Run `install_android.sh`.
8. Change directory to the root of Samba source code and run `make distclean`.
9. Repeat step 6-12 for all desired ABI's.
10. Make sure to change app's `build.gradle` to include only ABI's that Samba was built
    for in previous steps.
11. Compile SambaDocumentsProvider using Android Studio.


### Building on a VM

Building on Macs is not natively supported, but a VM / [Multipass](https://multipass.run/)
can be used to compile. Here are steps for multipass that worked on an Intel mac.

1. Follow the setup steps on https://multipass.run/
2. Creata new instance: `multipass launch --name ndk --disk 10G`
3. `multipass shell`
4. Copy the download link of the Linux SDK on https://developer.android.com/ndk/downloads,
   and use `wget` to download it, and then `unzip` it
5. Set `$NDK` to the unzipped path: `export NDK=$PWD/`
6. `sudo apt install python-is-python2`
7. `git clone https://github.com/mauricelam/samba && git checkout android`
8. Follow the build steps above

## Discussion
Please go to our [Google group][discussion] to discuss any issues.


[discussion]: https://groups.google.com/forum/#!forum/samba-documents-provider

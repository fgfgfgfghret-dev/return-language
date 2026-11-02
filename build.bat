@echo off
echo Building BITS...
nasm -f bin ARIF_8\boot.asm -o ARIF_8\boot.bin
nasm -f bin ARIF_8\kernel.asm -o ARIF_8\kernel.bin
copy /b ARIF_8\boot.bin + ARIF_8\kernel.bin ARIF_8\os-image.bin
qemu-system-i386 -drive format=raw,file=ARIF_8\os-image.bin,if=floppy -no-reboot
pause

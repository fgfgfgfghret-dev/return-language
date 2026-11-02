package Compiler;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Compiler {
    static Map<String, String> variables = new HashMap<>();
    static Map<String, Integer> arrays = new HashMap<>();
    static int memoryPointer = 0x1000;
    static int labelCounter = 0;
    static int memOffset = 0xB8000;
    static StringBuilder asm = new StringBuilder();
    
    static Stack<String> endIfLabels = new Stack<>();
    static Stack<String> endLoopLabels = new Stack<>();
    static Stack<String> funcSkipLabels = new Stack<>(); // <--- НОВОЕ: Прыжок через функции

    static Map<Character, Integer> scancodes = new HashMap<>();
    static {
        scancodes.put('a', 0x1E); scancodes.put('b', 0x30); scancodes.put('c', 0x2E);
        scancodes.put('d', 0x20); scancodes.put('e', 0x12); scancodes.put('f', 0x21);
        scancodes.put('g', 0x22); scancodes.put('h', 0x23); scancodes.put('i', 0x17);
        scancodes.put('j', 0x24); scancodes.put('k', 0x25); scancodes.put('l', 0x26);
        scancodes.put('m', 0x32); scancodes.put('n', 0x31); scancodes.put('o', 0x18);
        scancodes.put('p', 0x19); scancodes.put('q', 0x10); scancodes.put('r', 0x13);
        scancodes.put('s', 0x1F); scancodes.put('t', 0x14); scancodes.put('u', 0x16);
        scancodes.put('v', 0x2F); scancodes.put('w', 0x11); scancodes.put('x', 0x2D);
        scancodes.put('y', 0x15); scancodes.put('z', 0x2C); scancodes.put(' ', 0x39);
        scancodes.put('\n', 0x1C);
    }

    public static void main(String[] args) {
        System.out.println("=== BITS Return Compiler v9.1 (No Fallthrough) ===");
        createFolder("ARIF_8"); createFolder("User");

        String sourceCode = readFile("main.ret");
        if (sourceCode == null) {
            System.out.println("[ERR] Файл main.ret не найден!");
            return;
        }

        asm.append("bits 32\norg 0x7E00\nstart:\n");
        compile(sourceCode);
        appendFunctions();
        asm.append("jmp $\n");

        writeFile("ARIF_8/kernel.asm", asm.toString());
        generateBootloader();
        generateBatFile();
        System.out.println("[SUCCESS] Компиляция завершена! Запускай build.bat");
    }

    private static void compile(String source) {
        String[] lines = source.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].split("//")[0].trim();
            if (line.isEmpty()) continue;

            try {
                // 1. ПЕРЕМЕННЫЕ
                if (line.startsWith("j ") && !line.contains("[")) {
                    String[] parts = line.replace(";", "").split(" ");
                    variables.put(parts[1], Integer.toHexString(memoryPointer));
                    asm.append("mov dword [0x").append(Integer.toHexString(memoryPointer)).append("], ").append(parts[3]).append("\n");
                    memoryPointer += 4;
                }
                // 2. МАССИВЫ
                else if (line.startsWith("j ") && line.contains("[")) {
                    String name = line.substring(2, line.indexOf("[")).trim();
                    int size = Integer.parseInt(line.substring(line.indexOf("[")+1, line.indexOf("]")));
                    arrays.put(name, memoryPointer);
                    memoryPointer += (size * 4);
                }
                else if (line.contains("[") && line.contains("] =")) {
                    String name = line.substring(0, line.indexOf("[")).trim();
                    int index = Integer.parseInt(line.substring(line.indexOf("[")+1, line.indexOf("]")));
                    String val = line.substring(line.indexOf("=")+1).replace(";", "").trim();
                    int addr = arrays.get(name) + (index * 4);
                    asm.append("mov dword [0x").append(Integer.toHexString(addr)).append("], ").append(val).append("\n");
                }
                // 3. ОЧИСТКА ЭКРАНА
                else if (line.startsWith("clow(")) {
                    String color = line.substring(5, line.indexOf(")")).toUpperCase();
                    asm.append("mov edi, 0xB8000\nmov ecx, 2000\n");
                    if (color.equals("BLUE")) asm.append("mov ax, 0x1020\n");
                    else if (color.equals("RED")) asm.append("mov ax, 0x4020\n");
                    else if (color.equals("GREEN")) asm.append("mov ax, 0x2020\n");
                    else asm.append("mov ax, 0x0720\n");
                    asm.append("rep stosw\n");
                    memOffset = 0xB8000;
                }
                // 4. ВВОД
                else if (line.startsWith("P(\"")) {
                    String expected = line.substring(3, line.indexOf("\"):")).toLowerCase();
                    String success = "in_ok_" + labelCounter, fail = "in_fail_" + labelCounter++;
                    for (char c : expected.toCharArray()) {
                        asm.append("call wait_key\ncmp al, 0x").append(Integer.toHexString(scancodes.getOrDefault(c, 0))).append("\njne ").append(fail).append("\n");
                    }
                    asm.append("jmp ").append(success).append("\n").append(fail).append(":\njmp start\n").append(success).append(":\n");
                }
                else if (line.startsWith("exit p;")) { /* Закрытие P */ }
                
                // 5. ВЫВОД
                else if (line.equals("allow:")) {
                    if (i + 1 < lines.length) {
                        String next = lines[i + 1].split("//")[0].trim();
                        if (next.startsWith("\"")) {
                            String[] parts = next.split("\\+");
                            for (String part : parts) {
                                part = part.trim();
                                if (part.startsWith("\"") && part.endsWith("\"")) {
                                    String text = part.substring(1, part.length() - 1);
                                    for (char c : text.toCharArray()) {
                                        asm.append("mov byte [0x").append(Integer.toHexString(memOffset)).append("], ").append((int)c).append("\n");
                                        asm.append("mov byte [0x").append(Integer.toHexString(memOffset+1)).append("], 0x0A\n");
                                        memOffset += 2;
                                    }
                                } else if (variables.containsKey(part)) {
                                    asm.append("mov eax, dword [0x").append(variables.get(part)).append("]\nmov esi, 0x").append(Integer.toHexString(memOffset)).append("\ncall print_num\n");
                                    memOffset += 20;
                                }
                            }
                            i++;
                        }
                    }
                }
                else if (line.startsWith("allow.time(")) {
                    asm.append("mov esi, 0x").append(Integer.toHexString(memOffset)).append("\ncall print_time\n");
                    memOffset += 20;
                }
                // 6. УСЛОВИЯ
                else if (line.startsWith("E(")) {
                    String cond = line.substring(2, line.indexOf("):"));
                    String lbl = "if_end_" + (labelCounter++);
                    endIfLabels.push(lbl);
                    String[] andConditions = cond.split("&");
                    for (String c : andConditions) {
                        c = c.trim();
                        String[] parts = c.split("[><=]");
                        asm.append("mov eax, dword [0x").append(variables.get(parts[0].trim())).append("]\ncmp eax, ").append(parts[1].trim()).append("\n");
                        if (c.contains(">")) asm.append("jle ").append(lbl).append("\n");
                        else if (c.contains("<")) asm.append("jge ").append(lbl).append("\n");
                        else asm.append("jne ").append(lbl).append("\n");
                    }
                }
                else if (line.startsWith("exit E;")) {
                    if (!endIfLabels.isEmpty()) asm.append(endIfLabels.pop()).append(":\n");
                }
                // 7. ЦИКЛЫ
                else if (line.startsWith("L(")) {
                    String cond = line.substring(2, line.indexOf("):"));
                    String start = "loop_start_" + labelCounter;
                    String end = "loop_end_" + labelCounter++;
                    endLoopLabels.push(end);
                    asm.append(start).append(":\n");
                    String[] parts = cond.split("[><=]");
                    asm.append("mov eax, dword [0x").append(variables.get(parts[0].trim())).append("]\ncmp eax, ").append(parts[1].trim()).append("\n");
                    if (cond.contains(">")) asm.append("jle ").append(end).append("\n");
                    else if (cond.contains("<")) asm.append("jge ").append(end).append("\n");
                    else asm.append("jne ").append(end).append("\n");
                }
                else if (line.startsWith("exit L;")) {
                    if (!endLoopLabels.isEmpty()) {
                        String end = endLoopLabels.pop();
                        asm.append("jmp ").append(end.replace("end", "start")).append("\n").append(end).append(":\n");
                    }
                }
                // 8. ФУНКЦИИ ROOT
                else if (line.startsWith("root(\"")) {
                    String funcName = line.substring(6, line.indexOf("\"):")).trim();
                    String skipLabel = "skip_func_" + labelCounter++; // Создаем метку для прыжка
                    asm.append("jmp ").append(skipLabel).append("\n"); // Прыгаем через функцию!
                    asm.append(funcName).append(":\n");
                    funcSkipLabels.push(skipLabel);
                }
                else if (line.startsWith("exit root;")) {
                    asm.append("ret\n");
                    if (!funcSkipLabels.isEmpty()) asm.append(funcSkipLabels.pop()).append(":\n"); // Ставим метку конца
                }
                // 8.1 ВЫЗОВ ФУНКЦИИ
                else if (line.startsWith("play(\"")) {
                    String funcName = line.substring(6, line.indexOf("\");")).trim();
                    asm.append("call ").append(funcName).append("\n");
                }
                // 9. МАТЕМАТИКА
                else if (line.contains("=") && (line.contains("+") || line.contains("-")) && !line.startsWith("j") && !line.contains("[")) {
                    String[] eqParts = line.split("=");
                    String dest = eqParts[0].trim();
                    String expr = eqParts[1].replace(";", "").trim();
                    if (expr.contains("+")) {
                        String[] operands = expr.split("\\+");
                        asm.append("mov eax, ").append(getOperand(operands[0].trim())).append("\n");
                        asm.append("add eax, ").append(getOperand(operands[1].trim())).append("\n");
                    } else {
                        String[] operands = expr.split("-");
                        asm.append("mov eax, ").append(getOperand(operands[0].trim())).append("\n");
                        asm.append("sub eax, ").append(getOperand(operands[1].trim())).append("\n");
                    }
                    asm.append("mov dword [0x").append(variables.get(dest)).append("], eax\n");
                }
                else if (line.endsWith("++")) asm.append("inc dword [0x").append(variables.get(line.replace("++", "").replace(";", "").trim())).append("]\n");
                else if (line.endsWith("--")) asm.append("dec dword [0x").append(variables.get(line.replace("--", "").replace(";", "").trim())).append("]\n");
                else if (line.contains("**")) {
                    String[] p = line.split("\\*\\*"); String addr = variables.get(p[0].trim());
                    asm.append("mov eax, dword [0x").append(addr).append("]\nimul eax, ").append(p[1].replace(";", "").trim()).append("\nmov dword [0x").append(addr).append("], eax\n");
                }
                else if (line.contains("//")) {
                    String[] p = line.split("//"); String addr = variables.get(p[0].trim());
                    asm.append("mov eax, dword [0x").append(addr).append("]\nmov ecx, ").append(p[1].replace(";", "").trim()).append("\ncdq\nidiv ecx\nmov dword [0x").append(addr).append("], eax\n");
                }
                // 10. ЖЕЛЕЗО
                else if (line.startsWith("asm(\"")) asm.append(line.substring(5, line.lastIndexOf("\""))).append("\n");
                else if (line.startsWith("port(")) {
                    String[] args = line.substring(5, line.indexOf(")")).split(",");
                    if (line.contains("->")) {
                        asm.append("mov dx, ").append(args[0]).append("\nin al, dx\nmovzx eax, al\nmov dword [0x").append(variables.get(line.substring(line.indexOf("->") + 2).replace(";", "").trim())).append("], eax\n");
                    } else {
                        asm.append("mov al, ").append(args[1]).append("\nmov dx, ").append(args[0]).append("\nout dx, al\n");
                    }
                }
                else if (line.startsWith("peek(")) {
                    asm.append("movzx eax, byte [").append(line.substring(5, line.indexOf(")"))).append("]\nmov dword [0x").append(variables.get(line.substring(line.indexOf("->") + 2).replace(";", "").trim())).append("], eax\n");
                }
                else if (line.startsWith("poke(")) {
                    String[] args = line.substring(5, line.indexOf(")")).split(",");
                    asm.append("mov byte [").append(args[0]).append("], ").append(args[1]).append("\n");
                }
                else if (line.startsWith("int(")) asm.append("int ").append(line.substring(4, line.indexOf(")"))).append("\n");
                else if (line.startsWith("halt(")) asm.append("hlt\n");
                else if (line.startsWith("start(QEMU);")) { /* Для батника */ }
            } catch (Exception e) {
                System.out.println("[ERR] Ошибка в строке: '" + line + "' -> " + e.getMessage());
            }
        }
    }

    private static String getOperand(String op) {
        if (variables.containsKey(op)) return "dword [0x" + variables.get(op) + "]";
        return op;
    }

    private static void appendFunctions() {
        asm.append("\nprint_num:\n    pusha\n    mov ecx, 0\n    mov ebx, 10\ndivide_loop:\n    xor edx, edx\n    div ebx\n    add dl, 48\n    push edx\n    inc ecx\n    test eax, eax\n    jnz divide_loop\nprint_loop:\n    pop edx\n    mov [esi], dl\n    mov byte [esi+1], 0x0A\n    add esi, 2\n    loop print_loop\n    popa\n    ret\n");
        asm.append("\nprint_time:\n    pusha\n    mov byte [esi], 49\n    mov byte [esi+1], 0x0A\n    mov byte [esi+2], 50\n    mov byte [esi+3], 0x0A\n    mov byte [esi+4], 58\n    mov byte [esi+5], 0x0A\n    mov byte [esi+6], 48\n    mov byte [esi+7], 0x0A\n    mov byte [esi+8], 48\n    mov byte [esi+9], 0x0A\n    popa\n    ret\n");
        asm.append("\nwait_key:\n    in al, 0x64\n    test al, 1\n    jz wait_key\n    in al, 0x60\n    cmp al, 0x80\n    jae wait_key\n    ret\n");
    }

    private static void createFolder(String name) { new File(name).mkdirs(); }
    private static String readFile(String path) { try { return new String(Files.readAllBytes(Paths.get(path))); } catch (IOException e) { return null; } }
    private static void writeFile(String path, String content) { try { Files.write(Paths.get(path), content.getBytes()); } catch (IOException e) {} }
    
    private static void generateBootloader() {
        String boot = "bits 16\n" +
            "org 0x7c00\n" +
            "start:\n" +
            "    cli\n" +
            "    xor ax, ax\n" +
            "    mov ds, ax\n" +
            "    mov es, ax\n" +
            "    mov ss, ax\n" +
            "    mov sp, 0x7c00\n" +
            "    mov [BOOT_DRIVE], dl\n" +
            "    mov ah, 0x02\n" +
            "    mov al, 10\n" +        // Читаем 10 секторов
            "    mov ch, 0\n" +
            "    mov cl, 2\n" +
            "    mov dh, 0\n" +
            "    mov dl, [BOOT_DRIVE]\n" +
            "    mov bx, 0x7E00\n" +
            "    int 0x13\n" +
            "    jc disk_error\n" +
            "    in al, 0x92\n" +
            "    or al, 2\n" +
            "    out 0x92, al\n" +
            "    lgdt [gdt_descriptor]\n" +
            "    mov eax, cr0\n" +
            "    or eax, 0x1\n" +
            "    mov cr0, eax\n" +
            "    jmp CODE_SEG:init_pm\n" +
            "disk_error:\n" +
            "    mov bl, ah\n" +
            "    mov ah, 0x0e\n" +
            "    mov al, 'E'\n" +
            "    int 0x10\n" +
            "    mov al, bl\n" +
            "    add al, 48\n" +
            "    int 0x10\n" +
            "    jmp $\n" +
            "bits 32\n" +
            "init_pm:\n" +
            "    mov ax, DATA_SEG\n" +
            "    mov ds, ax\n" +
            "    mov ss, ax\n" +
            "    mov es, ax\n" +
            "    mov fs, ax\n" +
            "    mov gs, ax\n" +
            "    mov ebp, 0x90000\n" +
            "    mov esp, ebp\n" +
            "    jmp 0x7E00\n" +
            "gdt_start:\n" +
            "    dd 0x0\n" +
            "    dd 0x0\n" +
            "gdt_code:\n" +
            "    dw 0xffff, 0x0\n" +
            "    db 0x0, 0x9a, 0xcf, 0x0\n" +
            "gdt_data:\n" +
            "    dw 0xffff, 0x0\n" +
            "    db 0x0, 0x92, 0xcf, 0x0\n" +
            "gdt_end:\n" +
            "gdt_descriptor:\n" +
            "    dw gdt_end - gdt_start - 1\n" +
            "    dd gdt_start\n" +
            "CODE_SEG equ gdt_code - gdt_start\n" +
            "DATA_SEG equ gdt_data - gdt_start\n" +
            "BOOT_DRIVE: db 0\n" +
            "times 510-($-$$) db 0\n" +
            "dw 0xaa55\n";
        writeFile("ARIF_8/boot.asm", boot);
    }
    
    private static void generateBatFile() {
        String bat = "@echo off\necho Building BITS...\nnasm -f bin ARIF_8\\boot.asm -o ARIF_8\\boot.bin\nnasm -f bin ARIF_8\\kernel.asm -o ARIF_8\\kernel.bin\ncopy /b ARIF_8\\boot.bin + ARIF_8\\kernel.bin ARIF_8\\os-image.bin\n" +
        "qemu-system-i386 -drive format=raw,file=ARIF_8\\os-image.bin,if=floppy -no-reboot\npause\n";
        writeFile("build.bat", bat);
    }
}
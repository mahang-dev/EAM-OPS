import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const env = Object.fromEntries(
  readFileSync("../.env", "utf8")
    .split(/\r?\n/)
    .filter((x) => x.includes("="))
    .map((x) => {
      const i = x.indexOf("=");
      return [x.slice(0, i).trim(), x.slice(i + 1)];
    }),
);
const password = env.DEMO_PASSWORD;
async function login(page: Page, user: string) {
  await page.goto("/#/login");
  await page.getByPlaceholder("请输入登录账号").fill(user);
  await page.getByPlaceholder("请输入密码").fill(password);
  await page.getByRole("button", { name: "登录系统" }).click();
  await expect(
    page.getByRole("heading", { name: "运维总览", exact: true }),
  ).toBeVisible();
}
async function nav(page: Page, path: string) {
  await page.goto("/#/" + path);
  await expect(page.locator(".el-loading-mask")).toHaveCount(0);
}
function item(page: Page, label: string) {
  return page
    .getByRole("dialog")
    .locator(".el-form-item")
    .filter({ has: page.locator(".el-form-item__label", { hasText: label }) });
}
async function fill(page: Page, label: string, value: string) {
  await item(page, label).locator("input,textarea").first().fill(value);
}
async function select(page: Page, label: string, value: string) {
  const combo = item(page, label).getByRole("combobox");
  await item(page, label).locator('.el-select').click();
  const listId = await combo.getAttribute("aria-controls");
  await page
    .locator("#" + listId)
    .getByRole("option", { name: value, exact: true })
    .click();
}
async function submit(page: Page) {
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
}
async function row(page: Page, text: string) {
  await page.getByPlaceholder("搜索关键词").fill(text);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const result = page
    .locator(".el-table__body tr")
    .filter({ hasText: text })
    .first();
  await expect(result).toBeVisible();
  return result;
}

test("完整业务闭环：登记 → 巡检异常 → 工单处理 → 验收恢复", async ({
  page,
}) => {
  const unique = Date.now().toString(),
    assetName = "验收设备-" + unique,
    code = "E2E-" + unique,
    templateName = "检查模板-" + unique,
    planName = "巡检计划-" + unique;
  await login(page, "asset");
  await nav(page, "assets");
  await page.getByRole("button", { name: "登记资产" }).click();
  await fill(page, "资产编号", code);
  await fill(page, "名称", assetName);
  await select(page, "设备分类", "服务器");
  await select(page, "安装位置", "A01 机柜");
  await select(page, "责任人", "模拟·资产管理员1");
  await submit(page);
  await row(page, assetName);
  await login(page, "supervisor");
  await nav(page, "templates");
  await page.getByRole("button", { name: "新建模板" }).click();
  await fill(page, "名称", templateName);
  await fill(page, "检查项目", "电源\n接口");
  await submit(page);
  await nav(page, "plans");
  await page.getByRole("button", { name: "新建计划" }).click();
  await fill(page, "名称", planName);
  await select(page, "巡检模板", templateName);
  await select(page, "执行人", "模拟·运维工程师1");
  await select(page, "执行周期", "每日");
  const today = new Date();
  await fill(
    page,
    "下次执行日期",
    `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`,
  );
  await item(page, "下次执行日期").locator("input").press("Enter");
  await select(page, "关联设备", assetName);
  await page.getByRole("dialog").getByText("新建计划", { exact: true }).click();
  await submit(page);
  let r = await row(page, planName);
  await r.getByRole("button", { name: "生成", exact: true }).click();
  await page.getByRole("button", { name: "确定", exact: true }).click();
  await expect(page.getByText("操作成功").first()).toBeVisible();
  await login(page, "engineer");
  await nav(page, "tasks");
  r = page.locator(".el-table__body tr").filter({ hasText: assetName }).first();
  await expect(r).toBeVisible();
  await r.getByRole("button", { name: "执行巡检" }).click();
  await select(page, "电源", "正常");
  await select(page, "接口", "异常");
  await fill(page, "结果说明", "模拟接口异常，需要处理");
  await submit(page);
  r = page.locator(".el-table__body tr").filter({ hasText: assetName }).first();
  await r.getByRole("button", { name: "异常转工单" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const title = "异常工单-" + unique;
  await fill(page, "标题", title);
  await submit(page);
  await login(page, "supervisor");
  await nav(page, "orders");
  r = await row(page, title);
  await r.getByRole("button", { name: "分派", exact: true }).click();
  await select(page, "执行人", "模拟·运维工程师1");
  await fill(page, "分派原因", "安排排障");
  await submit(page);
  await login(page, "engineer");
  await nav(page, "orders");
  r = await row(page, title);
  await r.getByRole("button", { name: "接单", exact: true }).click();
  await submit(page);
  r = await row(page, title);
  await r.getByRole("button", { name: "提交验收" }).click();
  await fill(page, "处理说明", "已检查接口并恢复连接");
  await submit(page);
  await login(page, "supervisor");
  await nav(page, "orders");
  r = await row(page, title);
  await r.getByRole("button", { name: "验收", exact: true }).click();
  await fill(page, "处理说明", "验证通过，设备运行正常");
  await item(page, "确认设备故障已恢复").getByRole("switch").click();
  await submit(page);
  r = await row(page, title);
  await expect(r).toContainText("已关闭");
  await nav(page, "assets");
  r = await row(page, assetName);
  await expect(r).toContainText("运行中");
  await nav(page, "dashboard");
  await expect(page.locator(".metric-card")).toHaveCount(4);
  await page.screenshot({
    path: "../docs/screenshots/dashboard.png",
    fullPage: true,
  });
});

test("管理页面可加载，审计人员没有修改入口", async ({ page }) => {
  await login(page, "admin");
  for (const path of [
    "assets",
    "approvals",
    "tasks",
    "plans",
    "templates",
    "orders",
    "alerts",
    "rules",
    "locations",
    "categories",
    "users",
    "departments",
    "roles",
    "audit",
    "notifications",
  ]) {
    await nav(page, path);
    await expect(
      page.getByText("数据加载失败，请检查权限或点击刷新重试"),
    ).toHaveCount(0);
  }
  await nav(page, "assets");
  await page.screenshot({
    path: "../docs/screenshots/assets.png",
    fullPage: true,
  });
  await page.goto("/#/login");
  await page.getByPlaceholder("请输入登录账号").fill("auditor");
  await page.getByPlaceholder("请输入密码").fill(password);
  await page.getByRole("button", { name: "登录系统" }).click();
  await expect(page.getByRole("heading", { name: "审计日志" })).toBeVisible();
  await expect(
    page.locator("nav").getByRole("link", { name: "设备资产" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "编辑", exact: true }),
  ).toHaveCount(0);
});

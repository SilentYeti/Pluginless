package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompanyBookTest {
    private final UUID owner = UUID.randomUUID(), manager = UUID.randomUUID(), worker = UUID.randomUUID(), customer = UUID.randomUUID();
    private final Accounts accounts = new Accounts(new HashMap<>(), 1000, () -> {});
    private final CompanyBook book = new CompanyBook();

    private CompanyBook.Company acme() {
        var company = book.create(owner, "Acme", 100, 1, accounts);
        book.hire(owner, company, manager, "manager");
        book.hire(manager, company, worker, "employee");
        return company;
    }

    @Test
    void creatingChargesTheFeeAndEnforcesNamesAndLimits() {
        assertNotNull(acme());
        assertEquals(900, accounts.balance(owner));
        assertEquals(100, accounts.balance(Accounts.SERVER));
        assertNull(book.create(owner, "Other", 100, 1, accounts));
        assertNull(book.create(manager, "acme", 100, 1, accounts));
        assertNull(book.create(manager, "no", 100, 1, accounts));
        assertNull(book.create(manager, "Pricey", 5000, 1, accounts));
        assertEquals(1000, accounts.balance(manager));
    }

    @Test
    void rolesLimitWhoCanSpendAndHire() {
        var company = acme();
        assertTrue(book.deposit(customer, company, 500, accounts));
        assertFalse(book.withdraw(worker, company, 10, accounts));
        assertFalse(book.withdraw(customer, company, 10, accounts));
        assertTrue(book.withdraw(manager, company, 10, accounts));
        assertFalse(book.hire(manager, company, customer, "manager"));
        assertFalse(book.hire(worker, company, customer, "employee"));
        assertFalse(book.fire(manager, company, manager.equals(owner) ? worker : owner));
        assertFalse(book.fire(worker, company, manager));
        assertTrue(book.fire(worker, company, worker));
        assertTrue(book.fire(owner, company, manager));
    }

    @Test
    void payrollPaysWhatTheAccountCovers() {
        var company = acme();
        book.deposit(owner, company, 150, accounts);
        assertTrue(book.wage(owner, company, manager, 100));
        assertTrue(book.wage(manager, company, worker, 100));
        assertFalse(book.wage(worker, company, worker, 999));
        assertEquals(java.util.List.of(company), book.payroll(accounts));
        assertEquals(1100 + 1000, accounts.balance(manager) + accounts.balance(worker));
        assertEquals(50, accounts.balance(CompanyBook.account(company.id)));
    }

    @Test
    void shopsSellStockAndBuyUpToTheirLimitStorageAndFunds() {
        var company = acme();
        var shop = book.open(worker, company, "Corner", 1);
        assertNull(shop);
        shop = book.open(manager, company, "Corner", 1);
        assertNull(book.open(manager, company, "Second", 1));
        assertTrue(book.offer(shop, "minecraft:bread", 10, 4, 5));
        assertFalse(book.buy(customer, shop, "minecraft:bread", 1, 0, accounts));
        shop.stock.put("minecraft:bread", 3L);
        assertTrue(book.buy(customer, shop, "minecraft:bread", 2, 2, accounts));
        assertEquals(980, accounts.balance(customer));
        assertEquals(18, accounts.balance(CompanyBook.account(company.id)));
        assertEquals(1L, shop.stock.get("minecraft:bread"));
        assertFalse(book.buy(customer, shop, "minecraft:bread", 2, 0, accounts));

        assertFalse(book.sell(customer, shop, "minecraft:bread", 6, 100, accounts, pay -> false));
        assertFalse(book.sell(customer, shop, "minecraft:bread", 5, 100, accounts, pay -> false));
        assertFalse(book.sell(customer, shop, "minecraft:bread", 4, 4, accounts, pay -> false));
        assertTrue(book.sell(customer, shop, "minecraft:bread", 4, 100, accounts, pay -> false));
        assertEquals(996, accounts.balance(customer));
        assertEquals(5L, shop.stock.get("minecraft:bread"));
        assertEquals(1, shop.offers.get("minecraft:bread").buyLimit());
        assertEquals(1, book.find("bread").size());
        assertEquals(1, book.find("ACME").size());
        assertTrue(book.find("iron").isEmpty());
    }

    @Test
    void adminShopsHaveUnlimitedStockAndUseTheServer() {
        var shop = book.open(owner, null, "Server", 0);
        book.offer(shop, "minecraft:coal", 5, 1, 0);
        assertFalse(book.can(owner, shop, "shops", false));
        assertTrue(book.buy(customer, shop, "minecraft:coal", 10, 99, accounts));
        assertEquals(50, accounts.balance(Accounts.SERVER));
        assertTrue(book.sell(customer, shop, "minecraft:coal", 7, 0, accounts, pay -> pay == 7));
        assertTrue(shop.stock.isEmpty());
    }

    @Test
    void dissolvingPaysTheOwnerAndReturnsStock() {
        var company = acme();
        book.deposit(customer, company, 300, accounts);
        var shop = book.open(owner, company, "Corner", 1);
        shop.stock.put("minecraft:bread", 3L);
        assertNull(book.dissolve(manager, company, accounts));
        assertEquals(Map.of("minecraft:bread", 3L), book.dissolve(owner, company, accounts));
        assertEquals(900 + 300, accounts.balance(owner));
        assertTrue(book.companies.isEmpty());
        assertTrue(book.shops.isEmpty());
    }
}

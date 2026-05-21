package dev.coachbot.web.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;

@PermitAll
public class MainLayout extends AppLayout {

    public MainLayout() {
        addToNavbar(buildHeader());
        addToDrawer(buildDrawer());
        setPrimarySection(Section.DRAWER);
    }

    private HorizontalLayout buildHeader() {
        var toggle = new DrawerToggle();

        var title = new H1("Coach-bot Admin");
        title.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.Margin.NONE);

        var logout = new Button("Logout");
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        logout.addClickListener(e -> UI.getCurrent().getPage().setLocation("/logout"));

        var header = new HorizontalLayout(toggle, title, logout);
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(title);
        header.setWidthFull();
        header.addClassNames(LumoUtility.Padding.Vertical.NONE, LumoUtility.Padding.Horizontal.MEDIUM);
        return header;
    }

    private VerticalLayout buildDrawer() {
        var agentsLink = new RouterLink("Agents", AgentsView.class);
        agentsLink.addClassNames(LumoUtility.Display.BLOCK, LumoUtility.Padding.SMALL);

        var layout = new VerticalLayout(
                new Span("Navigation"),
                new Hr(),
                agentsLink
        );
        layout.setPadding(true);
        layout.setSpacing(false);
        return layout;
    }
}
